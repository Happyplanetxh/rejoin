package com.example.loginassistant.mixin;

import com.example.loginassistant.LoginAssistantClient;
import net.minecraft.client.network.ClientCommonNetworkHandler;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientCommonNetworkHandler.class)
public abstract class ClientCommonNetworkHandlerMixin {
    @Inject(method = "onDisconnected", at = @At("HEAD"))
    private void loginassistant$inspectDisconnectReason(Text reason, CallbackInfo ci) {
        LoginAssistantClient.onDisconnectReason(reason);
    }
}
