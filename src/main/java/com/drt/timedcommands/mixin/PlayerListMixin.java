package com.drt.timedcommands.mixin;

import com.drt.timedcommands.TimedCommands;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
    @Inject(method = "isOp", at = @At("RETURN"), cancellable = true)
    private void timedCommands$applyTemporaryOperatorState(
            NameAndId nameAndId,
            CallbackInfoReturnable<Boolean> callback
    ) {
        if (TimedCommands.isTemporarilyDeopped(nameAndId.id())) {
            callback.setReturnValue(false);
            return;
        }

        if (TimedCommands.isTemporarilyOpped(nameAndId.id())) {
            callback.setReturnValue(true);
        }
    }
}
