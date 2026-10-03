package dev.entrapy.villagerdupe;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.network.chat.Component;

public final class VillagerDupeClient implements ClientModInitializer {
  private final VillagerDupeCommand command = new VillagerDupeCommand();

  @Override
  public void onInitializeClient() {
    command.loadSettings();
    ClientTickEvents.END_CLIENT_TICK.register(command::tick);
    ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> command.clearSession());
    ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
        dispatcher.register(literal("villagerdupe")
            .executes(context -> {
              context.getSource().sendFeedback(Component.literal(
                  command.arm(context.getSource().getClient())));
              return 1;
            })
            .then(literal("auto")
                .executes(context -> toggleAuto(context, "all"))
                .then(literal("saddle").executes(context -> toggleAuto(context, "saddle")))
                .then(literal("horse_armor").executes(context -> toggleAuto(context, "horse_armor")))
                .then(literal("carpet").executes(context -> toggleAuto(context, "carpet"))))));
  }

  private int toggleAuto(
      com.mojang.brigadier.context.CommandContext<
          net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> context,
      String filter) {
    context.getSource().sendFeedback(Component.literal(command.toggleAuto(filter)));
    return 1;
  }
}
