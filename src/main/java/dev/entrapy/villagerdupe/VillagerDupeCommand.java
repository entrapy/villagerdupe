package dev.entrapy.villagerdupe;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Handles one-shot and repeated villager offhand interactions. */
final class VillagerDupeCommand {
  private static final long AUTO_INTERACT_INTERVAL_MS = 500L;
  private static final long SHEARS_RETRY_INTERVAL_MS = 1000L;
  private static final long SHEARS_SWAP_TIMEOUT_MS = 2000L;
  private static final int OFFHAND_SWAP_BUTTON = 40;

  private int targetId = -1;
  private UUID targetUuid;
  private boolean autoEnabled;
  private Filter autoFilter = Filter.ALL;
  private boolean shearsSwapRequested;
  private int shearsReadyTick;
  private long shearsSwapDeadlineMs;
  private long nextShearsAttemptAtMs;
  private long nextAutoInteractAtMs;

  String arm(Minecraft client) {
    if (autoEnabled) return "VillagerDupe auto is already enabled";
    if (targetUuid != null) {
      clearSession();
      return "VillagerDupe cancelled";
    }
    if (!inGame(client)) return "Not in game";
    LocalPlayer player = client.player;
    if ((client.gui.screen() != null && !(client.gui.screen() instanceof ChatScreen))
        || player.containerMenu != player.inventoryMenu) {
      return "Close the current screen before using VillagerDupe";
    }
    if (!player.inventoryMenu.getCarried().isEmpty()) return "Clear the inventory cursor first";
    Villager villager = nearestVillager(client, player);
    if (villager == null) return "No villager in interaction range";
    int emeraldSlot = emeraldHotbarSlot(player);
    if (emeraldSlot < 0) return "Put at least one emerald in your hotbar";
    if (!player.getOffhandItem().is(Items.SHEARS)) {
      if (shearsInventorySlot(player) < 0) return "Put shears in your inventory or offhand";
      if (player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) {
        return "Equip shears manually; VillagerDupe will not replace an offhand totem";
      }
    }

    selectEmerald(player, emeraldSlot);
    targetId = villager.getId();
    targetUuid = villager.getUUID();
    return "VillagerDupe armed; waiting for horse armor, carpet, or saddle in the villager's hand";
  }

  String toggleAuto(String filterToken) {
    Filter requested = Filter.fromToken(filterToken);
    boolean disable = autoEnabled && autoFilter == requested;
    autoEnabled = !disable;
    autoFilter = requested;
    clearSession();
    nextAutoInteractAtMs = 0L;
    nextShearsAttemptAtMs = 0L;
    String result = autoEnabled
        ? "VillagerDupe auto enabled for " + autoFilter.label
            + "; waiting for a nearby villager, hotbar emerald, and shears"
        : "VillagerDupe auto disabled";
    return saveSettings() ? result : result + "; could not save settings";
  }

  void tick(Minecraft client) {
    if (autoEnabled) {
      tickAuto(client);
      return;
    }
    if (targetUuid == null) return;
    if (!inGame(client)) {
      stop(client, "VillagerDupe cancelled: left the world");
      return;
    }
    if (client.gui.screen() instanceof ChatScreen) return;
    if (client.gui.screen() != null || client.player.containerMenu != client.player.inventoryMenu) {
      stop(client, "VillagerDupe cancelled: opened another screen");
      return;
    }
    LocalPlayer player = client.player;
    if (!(client.level.getEntity(targetId) instanceof Villager villager)
        || !villager.getUUID().equals(targetUuid) || !inReach(player, villager)) {
      stop(client, "VillagerDupe cancelled: villager left interaction range");
      return;
    }
    if (!player.getMainHandItem().is(Items.EMERALD)) {
      stop(client, "VillagerDupe cancelled: keep an emerald selected");
      return;
    }
    if (!ensureShears(client, false) || !Filter.ALL.matches(villager.getMainHandItem())) return;
    if (!interact(client, villager)) {
      stop(client, "VillagerDupe cancelled: invalid interaction position");
      return;
    }
    stop(client, "VillagerDupe sent one offhand interaction");
  }

  private void tickAuto(Minecraft client) {
    if (!inGame(client) || client.gui.screen() != null) return;
    LocalPlayer player = client.player;
    if (player.containerMenu != player.inventoryMenu || !player.inventoryMenu.getCarried().isEmpty()) return;
    Villager villager = nearestVillager(client, player);
    if (villager == null) return;
    int emeraldSlot = emeraldHotbarSlot(player);
    if (emeraldSlot < 0) return;
    selectEmerald(player, emeraldSlot);
    if (!ensureShears(client, true)) return;
    long now = System.currentTimeMillis();
    if (now < nextAutoInteractAtMs || !autoFilter.matches(villager.getMainHandItem())) return;
    if (interact(client, villager)) nextAutoInteractAtMs = now + AUTO_INTERACT_INTERVAL_MS;
  }

  private boolean ensureShears(Minecraft client, boolean auto) {
    LocalPlayer player = client.player;
    if (player.getOffhandItem().is(Items.SHEARS)) {
      if (player.tickCount < shearsReadyTick) return false;
      shearsSwapRequested = false;
      return true;
    }
    long now = System.currentTimeMillis();
    if (shearsSwapRequested) {
      if (now < shearsSwapDeadlineMs) return false;
      shearsSwapRequested = false;
      nextShearsAttemptAtMs = now + SHEARS_RETRY_INTERVAL_MS;
      if (!auto) stop(client, "VillagerDupe cancelled: shears offhand swap was not confirmed");
      return false;
    }
    if (now < nextShearsAttemptAtMs || !player.inventoryMenu.getCarried().isEmpty()) return false;
    int shearsSlot = shearsInventorySlot(player);
    if (shearsSlot < 0 || player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) {
      if (!auto) stop(client, shearsSlot < 0
          ? "VillagerDupe cancelled: shears are no longer available"
          : "VillagerDupe cancelled: equip shears manually to keep the offhand totem");
      return false;
    }
    if (player.isCreative() || player.isSpectator() || client.gameMode == null) {
      if (!auto) stop(client, "VillagerDupe cancelled: inventory swap is unavailable");
      return false;
    }
    client.gameMode.handleContainerInput(
        player.inventoryMenu.containerId, shearsSlot, OFFHAND_SWAP_BUTTON, ContainerInput.SWAP, player);
    shearsSwapRequested = true;
    shearsReadyTick = player.tickCount + 2;
    shearsSwapDeadlineMs = now + SHEARS_SWAP_TIMEOUT_MS;
    return false;
  }

  private static boolean interact(Minecraft client, Villager villager) {
    Vec3 location = villager.getBoundingBox().getCenter().subtract(villager.position());
    if (!Double.isFinite(location.x) || !Double.isFinite(location.y)
        || !Double.isFinite(location.z)) return false;
    client.getConnection().send(new ServerboundInteractPacket(
        villager.getId(), InteractionHand.OFF_HAND, location, false));
    return true;
  }

  private static boolean inGame(Minecraft client) {
    return client != null && client.player != null && client.level != null
        && client.gameMode != null && client.getConnection() != null;
  }

  private static Villager nearestVillager(Minecraft client, LocalPlayer player) {
    double range = Math.max(0.0, player.entityInteractionRange());
    AABB search = player.getBoundingBox().inflate(range);
    Villager nearest = null;
    double nearestDistance = Double.POSITIVE_INFINITY;
    for (Villager villager : client.level.getEntitiesOfClass(Villager.class, search,
        candidate -> candidate.isAlive() && !candidate.isRemoved())) {
      if (!inReach(player, villager)) continue;
      double distance = villager.distanceToSqr(player);
      if (distance < nearestDistance) {
        nearest = villager;
        nearestDistance = distance;
      }
    }
    return nearest;
  }

  private static boolean inReach(LocalPlayer player, Villager villager) {
    return villager.isAlive() && !villager.isRemoved()
        && player.isWithinEntityInteractionRange(villager, 0.0);
  }

  private static int emeraldHotbarSlot(LocalPlayer player) {
    int selected = player.getInventory().getSelectedSlot();
    if (player.getInventory().getItem(selected).is(Items.EMERALD)) return selected;
    for (int slot = 0; slot < 9; slot++) {
      if (player.getInventory().getItem(slot).is(Items.EMERALD)) return slot;
    }
    return -1;
  }

  private static int shearsInventorySlot(LocalPlayer player) {
    for (int slot = 9; slot <= 44; slot++) {
      if (player.inventoryMenu.getSlot(slot).getItem().is(Items.SHEARS)) return slot;
    }
    return -1;
  }

  private static void selectEmerald(LocalPlayer player, int slot) {
    if (player.getInventory().getSelectedSlot() == slot) return;
    player.getInventory().setSelectedSlot(slot);
    player.connection.send(new ServerboundSetCarriedItemPacket(slot));
  }

  private void stop(Minecraft client, String message) {
    clearSession();
    if (client != null && client.player != null) {
      client.gui.hud.getChat().addClientSystemMessage(Component.literal(message));
    }
  }

  void clearSession() {
    targetId = -1;
    targetUuid = null;
    shearsSwapRequested = false;
    shearsReadyTick = 0;
    shearsSwapDeadlineMs = 0L;
  }

  void loadSettings() {
    Path path = settingsPath();
    if (!Files.isRegularFile(path)) return;
    Properties settings = new Properties();
    try (var input = Files.newInputStream(path)) {
      settings.load(input);
      autoEnabled = Boolean.parseBoolean(settings.getProperty("auto", "false"));
      autoFilter = Filter.fromToken(settings.getProperty("filter", "all"));
    } catch (IOException exception) {
      System.err.println("VillagerDupe: could not read settings: " + exception.getMessage());
    }
  }

  private boolean saveSettings() {
    Properties settings = new Properties();
    settings.setProperty("auto", Boolean.toString(autoEnabled));
    settings.setProperty("filter", autoFilter.token);
    try (var output = Files.newOutputStream(settingsPath())) {
      settings.store(output, "VillagerDupe client settings");
      return true;
    } catch (IOException exception) {
      System.err.println("VillagerDupe: could not save settings: " + exception.getMessage());
      return false;
    }
  }

  private static Path settingsPath() {
    return FabricLoader.getInstance().getConfigDir().resolve("villagerdupe.properties");
  }

  private enum Filter {
    ALL("all", "all supported items"),
    SADDLE("saddle", "saddles"),
    HORSE_ARMOR("horse_armor", "horse armor"),
    CARPET("carpet", "carpets");

    private final String token;
    private final String label;

    Filter(String token, String label) {
      this.token = token;
      this.label = label;
    }

    private boolean matches(ItemStack stack) {
      return switch (this) {
        case ALL -> SADDLE.matches(stack) || HORSE_ARMOR.matches(stack) || CARPET.matches(stack);
        case SADDLE -> stack.is(Items.SADDLE);
        case HORSE_ARMOR -> stack.is(Items.LEATHER_HORSE_ARMOR)
            || stack.is(Items.COPPER_HORSE_ARMOR) || stack.is(Items.IRON_HORSE_ARMOR)
            || stack.is(Items.GOLDEN_HORSE_ARMOR) || stack.is(Items.DIAMOND_HORSE_ARMOR)
            || stack.is(Items.NETHERITE_HORSE_ARMOR);
        case CARPET -> stack.is(ItemTags.WOOL_CARPETS)
            || stack.is(Items.MOSS_CARPET) || stack.is(Items.PALE_MOSS_CARPET);
      };
    }

    private static Filter fromToken(String token) {
      for (Filter filter : values()) {
        if (filter.token.equalsIgnoreCase(token)) return filter;
      }
      return ALL;
    }
  }
}
