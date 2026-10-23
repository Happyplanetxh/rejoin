package com.example.loginassistant;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public final class LoginAssistantClient implements ClientModInitializer {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type CONFIG_TYPE = new TypeToken<Config>() {}.getType();
    private static Config config = new Config();
    private static Path configPath;
    private static String status = "未连接";
    private static boolean loginPromptSeen;
    private static long lastLoginAttempt;
    private static long blockedUntil;
    private static String banReason = "";
    private static ServerInfo lastServerInfo;
    private static String lastServerAddress;
    private static long rejoinAt;
    private static boolean disconnectWasBan;

    @Override
    public void onInitializeClient() {
        MinecraftClient client = MinecraftClient.getInstance();
        configPath = client.runDirectory.toPath().resolve("config/loginassistant.json");
        loadConfig();
        registerCommands();
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> inspectMessage(message.getString()));
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> inspectMessage(message.getString()));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> onDisconnected(mc));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            rememberCurrentServer(mc);
            if (mc.player != null && mc.getNetworkHandler() != null) {
                String server = currentServerKey(mc);
                ServerConfig sc = server == null ? null : config.servers.get(server);
                if (sc != null && sc.autoLogin && !sc.knownPassword.isBlank() && loginPromptSeen && !isBlocked()) {
                    long now = System.currentTimeMillis();
                    if (now - lastLoginAttempt >= 5000L) {
                        lastLoginAttempt = now;
                        loginPromptSeen = false;
                        mc.getNetworkHandler().sendChatCommand("login " + sc.knownPassword);
                        status = "已发送已保存的登录密码，等待服务器确认";
                    }
                }
            }

            // 自动重连仅用于普通断线；识别到临时封禁时绝不自动重试。
            if (rejoinAt > 0 && System.currentTimeMillis() >= rejoinAt) {
                rejoinAt = 0;
                if (config.rejoinEnabled && !disconnectWasBan && !isBlocked() && lastServerInfo != null && mc.player == null) {
                    connectToLastServer(mc);
                }
            }
        });
    }

    private static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
            literal("assist")
                .executes(ctx -> { MinecraftClient.getInstance().setScreen(new AssistantScreen(null)); return 1; })
                .then(literal("status").executes(ctx -> { tell(status + (isBlocked() ? "；封禁保护剩余 " + remainingSeconds() + " 秒" : "")); return 1; }))
                .then(literal("add").then(argument("password", StringArgumentType.greedyString()).executes(ctx -> {
                    String p = StringArgumentType.getString(ctx, "password");
                    if (p.isBlank()) { tell("密码不能为空"); return 0; }
                    ServerConfig sc = serverConfig();
                    if (!sc.candidates.contains(p) && sc.candidates.size() < 500) sc.candidates.add(p);
                    saveConfig(); tell("已保存本地候选；不会自动测试候选"); return 1;
                })))
                .then(literal("known").then(argument("password", StringArgumentType.greedyString()).executes(ctx -> {
                    serverConfig().knownPassword = StringArgumentType.getString(ctx, "password");
                    saveConfig(); tell("已保存当前服务器的已知密码（明文）"); return 1;
                })))
                .then(literal("search").then(argument("keyword", StringArgumentType.greedyString()).executes(ctx -> {
                    String key = StringArgumentType.getString(ctx, "keyword").toLowerCase(Locale.ROOT);
                    List<String> found = serverConfig().candidates.stream().filter(p -> p.toLowerCase(Locale.ROOT).contains(key)).limit(10).toList();
                    tell(found.isEmpty() ? "没有匹配的本地候选" : "本地候选：" + String.join(" | ", found)); return 1;
                })))
                .then(literal("generate").then(argument("keyword", StringArgumentType.greedyString()).executes(ctx -> {
                    generateCandidates(StringArgumentType.getString(ctx, "keyword")); saveConfig(); tell("已生成本地候选；需手动选择，不会自动提交"); return 1;
                })))
                .then(literal("auto").then(argument("enabled", StringArgumentType.word()).executes(ctx -> {
                    String v = StringArgumentType.getString(ctx, "enabled");
                    if (!isOnOff(v)) { tell("用法：/assist auto on|off"); return 0; }
                    serverConfig().autoLogin = v.equalsIgnoreCase("on"); saveConfig(); tell("自动登录：" + (serverConfig().autoLogin ? "开启" : "关闭")); return 1;
                })))
                .then(literal("rejoin").then(argument("enabled", StringArgumentType.word()).executes(ctx -> {
                    String v = StringArgumentType.getString(ctx, "enabled");
                    if (!isOnOff(v)) { tell("用法：/assist rejoin on|off"); return 0; }
                    config.rejoinEnabled = v.equalsIgnoreCase("on"); saveConfig(); tell("普通断线自动重连：" + (config.rejoinEnabled ? "开启" : "关闭") + "；临时封禁期间始终暂停"); return 1;
                })))
                .then(literal("unblock").executes(ctx -> {
                    if (isBlocked()) { tell("仍处于封禁保护期，剩余 " + remainingSeconds() + " 秒；不会自动重连"); return 0; }
                    disconnectWasBan = false; tell("当前没有活动中的封禁保护"); return 1;
                }))
                .then(literal("connect").executes(ctx -> {
                    MinecraftClient client = MinecraftClient.getInstance();
                    if (isBlocked()) { tell("仍处于临时封禁保护期，剩余 " + remainingSeconds() + " 秒；请等待服务器限制解除"); return 0; }
                    if (lastServerInfo == null) { tell("没有记录到上次连接的服务器"); return 0; }
                    disconnectWasBan = false;
                    rejoinAt = 0;
                    connectToLastServer(client);
                    return 1;
                }))
        ));
    }

    private static boolean isOnOff(String value) {
        return value.equalsIgnoreCase("on") || value.equalsIgnoreCase("off");
    }

    private static void inspectMessage(String raw) {
        String s = raw.toLowerCase(Locale.ROOT);
        if (containsAny(s, "/login", "please login", "please log in", "请登录", "请输入密码")) {
            if (!isBlocked()) { loginPromptSeen = true; status = "服务器要求登录"; }
            return;
        }
        if (containsAny(s, "logged in", "login successful", "登录成功", "成功登录")) {
            loginPromptSeen = false; status = "登录成功（聊天提示）"; return;
        }
        if (containsAny(s, "incorrect password", "wrong password", "密码错误", "登录失败")) {
            loginPromptSeen = false; status = "登录失败；自动登录已停止";
        }
        if (containsAny(s, "temporarily banned", "temporary ban", "too many failed login attempts", "too many incorrect passwords", "临时封禁", "暂时封禁")) {
            activateBanProtection(raw);
        }
    }

    public static void onDisconnectReason(Text reason) {
        if (reason == null) return;
        String raw = reason.getString();
        String s = raw.toLowerCase(Locale.ROOT);
        if (containsAny(s, "temporarily banned", "temporary ban", "too many failed login attempts", "too many incorrect passwords", "临时封禁", "暂时封禁")) {
            activateBanProtection(raw);
            disconnectWasBan = true;
            rejoinAt = 0;
        }
    }

    private static void activateBanProtection(String reason) {
        long now = System.currentTimeMillis();
        if (blockedUntil <= now) blockedUntil = now + 10 * 60 * 1000L;
        banReason = reason;
        loginPromptSeen = false;
        rejoinAt = 0;
        disconnectWasBan = true;
        status = "检测到临时封禁；自动登录和自动重连已暂停";
    }

    private static void onDisconnected(MinecraftClient mc) {
        loginPromptSeen = false;
        if (isBlocked() || disconnectWasBan) {
            status = "因临时封禁保护，未安排自动重连";
            rejoinAt = 0;
            return;
        }
        if (config.rejoinEnabled && lastServerInfo != null) {
            rejoinAt = System.currentTimeMillis() + Math.max(1, config.rejoinDelaySeconds) * 1000L;
            status = "普通断线；将在 " + config.rejoinDelaySeconds + " 秒后尝试重连";
        }
    }

    private static void rememberCurrentServer(MinecraftClient client) {
        ServerInfo info = client.getCurrentServerEntry();
        if (info != null) {
            lastServerInfo = info;
            lastServerAddress = info.address;
        }
    }

    private static void connectToLastServer(MinecraftClient client) {
        if (lastServerInfo == null || isBlocked() || disconnectWasBan) return;
        try {
            ConnectScreen.connect(new TitleScreen(), client, ServerAddress.parse(lastServerInfo.address), lastServerInfo, false, null);
            status = "正在重新连接到上次服务器";
        } catch (RuntimeException ex) {
            status = "重连失败：" + ex.getClass().getSimpleName();
        }
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) if (text.contains(needle)) return true;
        return false;
    }

    private static long remainingSeconds() {
        return Math.max(0, (blockedUntil - System.currentTimeMillis() + 999) / 1000);
    }

    private static boolean isBlocked() {
        return System.currentTimeMillis() < blockedUntil;
    }

    private static void generateCandidates(String keyword) {
        if (keyword == null || keyword.isBlank()) return;
        ServerConfig sc = serverConfig();
        String k = keyword.trim();
        LinkedHashSet<String> values = new LinkedHashSet<>();
        values.add(k); values.add(k.toLowerCase(Locale.ROOT)); values.add(k.toUpperCase(Locale.ROOT));
        for (int year = 2020; year <= 2030; year++) { values.add(k + year); values.add(k + "!" + year); }
        for (int n = 0; n <= 99; n++) { values.add(k + n); values.add(k + String.format(Locale.ROOT, "%02d", n)); }
        values.add(k + "!"); values.add(k + "123"); values.add(k + "123!");
        for (String value : values) if (sc.candidates.size() < 500 && !sc.candidates.contains(value)) sc.candidates.add(value);
    }

    private static String currentServerKey(MinecraftClient client) {
        ServerInfo info = client.getCurrentServerEntry();
        return info == null ? null : info.address.toLowerCase(Locale.ROOT);
    }

    private static ServerConfig serverConfig() {
        MinecraftClient c = MinecraftClient.getInstance();
        String key = currentServerKey(c);
        if (key == null) key = "default";
        return config.servers.computeIfAbsent(key, ignored -> new ServerConfig());
    }

    private static void tell(String message) {
        MinecraftClient c = MinecraftClient.getInstance();
        if (c.player != null) c.player.sendMessage(Text.literal("[LoginAssistant] " + message).formatted(Formatting.AQUA), false);
        else status = message;
    }

    private static void loadConfig() {
        try {
            Files.createDirectories(configPath.getParent());
            if (Files.exists(configPath)) config = GSON.fromJson(Files.readString(configPath, StandardCharsets.UTF_8), CONFIG_TYPE);
            if (config == null) config = new Config();
            if (config.servers == null) config.servers = new HashMap<>();
            if (config.rejoinDelaySeconds < 1 || config.rejoinDelaySeconds > 60) config.rejoinDelaySeconds = 3;
            for (ServerConfig sc : config.servers.values()) if (sc.candidates == null) sc.candidates = new ArrayList<>();
        } catch (Exception e) { config = new Config(); }
    }

    private static void saveConfig() {
        try {
            Files.createDirectories(configPath.getParent());
            Files.writeString(configPath, GSON.toJson(config), StandardCharsets.UTF_8);
        } catch (IOException e) { tell("配置保存失败：" + e.getMessage()); }
    }

    private static final class Config {
        boolean rejoinEnabled;
        int rejoinDelaySeconds = 3;
        Map<String, ServerConfig> servers = new HashMap<>();
    }

    private static final class ServerConfig {
        String knownPassword = "";
        boolean autoLogin;
        List<String> candidates = new ArrayList<>();
    }

    private static final class AssistantScreen extends Screen {
        private final Screen parent;
        private TextFieldWidget keyword;
        private TextFieldWidget password;
        private TextFieldWidget candidate;
        private String message = "密码保存在 config/loginassistant.json（明文）";
        private int page;
        private int selectedIndex = -1;

        protected AssistantScreen(Screen parent) { super(Text.literal("LoginAssistant")); this.parent = parent; }

        @Override protected void init() {
            int cx = width / 2;
            keyword = new TextFieldWidget(textRenderer, cx - 150, 38, 300, 20, Text.literal("关键词"));
            keyword.setPlaceholder(Text.literal("关键词 / 候选过滤词")); addDrawableChild(keyword);
            candidate = new TextFieldWidget(textRenderer, cx - 150, 64, 300, 20, Text.literal("候选密码"));
            candidate.setPlaceholder(Text.literal("手动输入候选密码")); addDrawableChild(candidate);
            password = new TextFieldWidget(textRenderer, cx - 150, 90, 300, 20, Text.literal("已知密码"));
            password.setPlaceholder(Text.literal("已知密码（自动登录使用）")); addDrawableChild(password);

            addDrawableChild(ButtonWidget.builder(Text.literal("添加候选"), b -> {
                String p = candidate.getText();
                if (p.isBlank()) { message = "先输入候选密码"; return; }
                ServerConfig sc = serverConfig();
                if (!sc.candidates.contains(p) && sc.candidates.size() < 500) sc.candidates.add(p);
                saveConfig(); message = "候选已保存到本地"; candidate.setText("");
            }).dimensions(cx - 150, 117, 96, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("生成候选"), b -> {
                generateCandidates(keyword.getText()); saveConfig(); message = "已生成本地候选，请在下方列表手动选择"; page = 0; selectedIndex = -1;
            }).dimensions(cx - 50, 117, 96, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("保存已知密码"), b -> {
                serverConfig().knownPassword = password.getText(); saveConfig(); message = "已保存当前服务器的已知密码（明文）";
            }).dimensions(cx + 50, 117, 100, 20).build());

            addDrawableChild(ButtonWidget.builder(Text.literal("自动登录: " + (serverConfig().autoLogin ? "开" : "关")), b -> {
                serverConfig().autoLogin = !serverConfig().autoLogin; saveConfig(); b.setMessage(Text.literal("自动登录: " + (serverConfig().autoLogin ? "开" : "关")));
            }).dimensions(cx - 150, 143, 145, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("普通断线重连: " + (config.rejoinEnabled ? "开" : "关")), b -> {
                config.rejoinEnabled = !config.rejoinEnabled; saveConfig(); b.setMessage(Text.literal("普通断线重连: " + (config.rejoinEnabled ? "开" : "关")));
            }).dimensions(cx + 5, 143, 145, 20).build());

            addDrawableChild(ButtonWidget.builder(Text.literal("上一页"), b -> { page = Math.max(0, page - 1); selectedIndex = -1; }).dimensions(cx - 150, height - 57, 70, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("下一页"), b -> {
                int maxPage = Math.max(0, (filteredCandidates().size() - 1) / 5);
                page = Math.min(maxPage, page + 1); selectedIndex = -1;
            }).dimensions(cx - 75, height - 57, 70, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("设为已知密码"), b -> {
                List<String> list = filteredCandidates();
                int idx = page * 5 + selectedIndex;
                if (selectedIndex < 0 || idx >= list.size()) { message = "先点击选择一个候选"; return; }
                password.setText(list.get(idx)); message = "已填入密码框；点击保存后才会保存为已知密码";
            }).dimensions(cx, height - 57, 105, 20).build());
            
            addDrawableChild(ButtonWidget.builder(Text.literal("删除所选"), b -> {
                List<String> list = filteredCandidates();
                int idx = page * 5 + selectedIndex;
                if (selectedIndex < 0 || idx >= list.size()) { 
                    message = "先点击选择一个候选"; 
                    return; 
                }
                serverConfig().candidates.remove(list.get(idx)); 
                saveConfig(); 
                selectedIndex = -1; 
                message = "候选已删除";
                this.clearAndInit(); 
            }).dimensions(cx + 110, height - 57, 80, 20).build());
            
            addDrawableChild(ButtonWidget.builder(Text.literal("关闭"), b -> close()).dimensions(cx - 45, height - 30, 90, 20).build());
        }

        private List<String> filteredCandidates() {
            String filter = keyword == null ? "" : keyword.getText().toLowerCase(Locale.ROOT);
            return serverConfig().candidates.stream().filter(p -> p.toLowerCase(Locale.ROOT).contains(filter)).toList();
        }

        @Override public boolean mouseClicked(Click click, boolean doubled) {
            if (click.button() == 0 && click.y() >= 174 && click.y() < 174 + 5 * 18) {
                int row = (int) ((click.y() - 174) / 18);
                int idx = page * 5 + row;
                List<String> list = filteredCandidates();
                if (idx >= 0 && idx < list.size()) {
                    selectedIndex = row;
                    message = "已选择第 " + (idx + 1) + " 项候选";
                    return true;
                }
            }
            return super.mouseClicked(click, doubled);
        }

        @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            renderBackground(context, mouseX, mouseY, delta);
            context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 12, 0xFFFFFF);
            super.render(context, mouseX, mouseY, delta);
            List<String> list = filteredCandidates();
            int start = page * 5;
            for (int row = 0; row < 5; row++) {
                int idx = start + row;
                String line = idx < list.size() ? ((row == selectedIndex ? "> " : "  ") + (idx + 1) + ". " + mask(list.get(idx))) : "";
                context.drawTextWithShadow(textRenderer, Text.literal(line), width / 2 - 145, 176 + row * 18, row == selectedIndex ? 0xFFFF55 : 0xDDDDDD);
            }
            context.drawCenteredTextWithShadow(textRenderer, Text.literal("状态: " + status), width / 2, 275, 0xAAAAFF);
            context.drawCenteredTextWithShadow(textRenderer, Text.literal(isBlocked() ? "封禁保护剩余: " + remainingSeconds() + " 秒；自动重连已暂停" : "候选数: " + list.size()), width / 2, 290, isBlocked() ? 0xFF7777 : 0xAAAAAA);
            context.drawCenteredTextWithShadow(textRenderer, Text.literal(message), width / 2, 308, 0xDDDDDD);
        }

        private String mask(String value) {
            if (value.length() <= 2) return "*".repeat(value.length());
            return value.substring(0, 1) + "*".repeat(Math.min(12, value.length() - 2)) + value.substring(value.length() - 1);
        }

        @Override public void close() { MinecraftClient.getInstance().setScreen(parent); }
    }
}
