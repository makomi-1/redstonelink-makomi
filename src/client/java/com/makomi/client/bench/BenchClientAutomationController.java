package com.makomi.client.bench;

import com.makomi.RedstoneLink;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

/**
 * bench 外部客户端自动入服控制器。
 * <p>
 * 该控制器只在 bench 配置启用时工作，负责：
 * </p>
 * <ul>
 *     <li>客户端启动后自动连接指定 dedicated server</li>
 *     <li>掉线或服务端重启后自动重试连接</li>
 * </ul>
 */
public final class BenchClientAutomationController {
	private static final long CONNECT_ATTEMPT_STALL_TIMEOUT_MS = 30_000L;
	private static BenchClientAutomationConfig config = BenchClientAutomationConfig.disabled();
	private static long nextConnectAttemptAtMs = Long.MAX_VALUE;
	private static long lastConnectAttemptAtMs = Long.MIN_VALUE;
	private static long connectAttemptStartedAtMs = Long.MIN_VALUE;
	private static boolean wasInWorldLastTick = false;
	private static boolean connectAttemptInProgress = false;
	private static boolean postJoinActionsApplied = false;
	private static long postJoinActionReadyAtMs = Long.MAX_VALUE;

	private BenchClientAutomationController() {
	}

	/**
	 * 初始化 bench 自动入服控制器。
	 */
	public static void initialize() {
		config = BenchClientAutomationConfig.load();
		if (!config.enabled()) {
			return;
		}

		nextConnectAttemptAtMs = System.currentTimeMillis() + config.initialConnectDelayMs();
		lastConnectAttemptAtMs = Long.MIN_VALUE;
		connectAttemptStartedAtMs = Long.MIN_VALUE;
		wasInWorldLastTick = false;
		connectAttemptInProgress = false;
		postJoinActionsApplied = false;
		postJoinActionReadyAtMs = Long.MAX_VALUE;
		ClientTickEvents.END_CLIENT_TICK.register(BenchClientAutomationController::onClientTick);
		RedstoneLink.LOGGER.info(
			"Bench client automation enabled. player={} server={} initialConnectDelayMs={} reconnectIntervalMs={} openTickChart={}",
			config.playerName(),
			config.serverAddress(),
			config.initialConnectDelayMs(),
			config.reconnectIntervalMs(),
			config.openTickChart()
		);
	}

	private static void onClientTick(Minecraft client) {
		if (!config.enabled()) {
			return;
		}
		if (client == null) {
			return;
		}
		boolean inWorld = client.player != null && client.level != null;
		if (inWorld) {
			clearConnectAttemptState();
			schedulePostJoinActionsIfNeeded();
			dismissTransientStartupScreen(client);
			runPostJoinActionsIfReady(client);
			wasInWorldLastTick = true;
			return;
		}
		resetPostJoinActionsAfterLeaveIfNeeded();
		long now = System.currentTimeMillis();
		reconcileConnectAttemptState(client, now);
		if (connectAttemptInProgress) {
			return;
		}

		if (now < nextConnectAttemptAtMs) {
			return;
		}

		attemptConnect(client, now);
	}

	private static void schedulePostJoinActionsIfNeeded() {
		if (wasInWorldLastTick) {
			return;
		}
		postJoinActionsApplied = false;
		postJoinActionReadyAtMs = System.currentTimeMillis() + config.postJoinActionDelayMs();
	}

	private static void attemptConnect(Minecraft client, long now) {
		ServerAddress serverAddress = ServerAddress.parseString(config.serverAddress());
		ServerData serverData = new ServerData("RedstoneLink Bench", config.serverAddress(), ServerData.Type.OTHER);
		serverData.setResourcePackStatus(ServerData.ServerPackStatus.ENABLED);
		Screen parentScreen = buildParentScreen(client);
		lastConnectAttemptAtMs = now;
		connectAttemptStartedAtMs = now;
		connectAttemptInProgress = true;
		nextConnectAttemptAtMs = now + config.reconnectIntervalMs();

		RedstoneLink.LOGGER.info(
			"Bench client automation connecting. player={} server={} screen={}",
			config.playerName(),
			config.serverAddress(),
			parentScreen.getClass().getSimpleName()
		);
		ConnectScreen.startConnecting(parentScreen, client, serverAddress, serverData, false, (TransferState)null);
	}

	private static void resetPostJoinActionsAfterLeaveIfNeeded() {
		if (!wasInWorldLastTick) {
			return;
		}
		wasInWorldLastTick = false;
		postJoinActionsApplied = false;
		postJoinActionReadyAtMs = Long.MAX_VALUE;
	}

	/**
	 * 统一维护“自动连接仍在进行中”状态，避免世界接收阶段再次发起同名连接。
	 * <p>
	 * 原版在进入世界前还会经历接收区块/加载关卡等过渡界面，这些阶段虽然
	 * `player/level` 尚未就绪，但已经属于同一轮连接流程，不能再次 startConnecting。
	 * </p>
	 */
	private static void reconcileConnectAttemptState(Minecraft client, long now) {
		Screen currentScreen = client.screen;
		if (isConnectionAttemptScreen(currentScreen)) {
			adoptObservedConnectionAttemptIfNeeded(currentScreen, now);
			return;
		}
		if (currentScreen instanceof DisconnectedScreen) {
			clearConnectAttemptState();
			return;
		}
		if (!connectAttemptInProgress) {
			return;
		}
		if (connectAttemptStartedAtMs != Long.MIN_VALUE && (now - connectAttemptStartedAtMs) < CONNECT_ATTEMPT_STALL_TIMEOUT_MS) {
			return;
		}

		RedstoneLink.LOGGER.warn(
			"Bench client automation cleared stalled connect attempt. player={} server={} screen={}",
			config.playerName(),
			config.serverAddress(),
			currentScreen == null ? "<null>" : currentScreen.getClass().getSimpleName()
		);
		clearConnectAttemptState();
	}

	/**
	 * 收编外部启动器已经触发的连接流程，避免 bench 控制器再抢发一轮同名连接。
	 */
	private static void adoptObservedConnectionAttemptIfNeeded(Screen currentScreen, long now) {
		if (connectAttemptInProgress) {
			return;
		}
		connectAttemptInProgress = true;
		connectAttemptStartedAtMs = now;
		nextConnectAttemptAtMs = now + config.reconnectIntervalMs();
		RedstoneLink.LOGGER.info(
			"Bench client automation adopted existing connect flow. player={} server={} screen={}",
			config.playerName(),
			config.serverAddress(),
			currentScreen.getClass().getSimpleName()
		);
	}

	/**
	 * 当前 screen 是否仍处于 bench 自动连接链路内部。
	 */
	private static boolean isConnectionAttemptScreen(Screen screen) {
		if (screen == null) {
			return false;
		}
		return screen instanceof ConnectScreen
			|| screen instanceof ReceivingLevelScreen
			|| screen instanceof LevelLoadingScreen
			|| screen instanceof ProgressScreen;
	}

	/**
	 * 清空连接中的运行态锁存。
	 */
	private static void clearConnectAttemptState() {
		connectAttemptInProgress = false;
		connectAttemptStartedAtMs = Long.MIN_VALUE;
	}

	private static void runPostJoinActionsIfReady(Minecraft client) {
		if (postJoinActionsApplied) {
			return;
		}
		if (System.currentTimeMillis() < postJoinActionReadyAtMs) {
			return;
		}

		ensureTickChartVisible(client);
		postJoinActionsApplied = true;
		postJoinActionReadyAtMs = Long.MAX_VALUE;
	}

	/**
	 * bench 观察模式默认需要 `F3+2` 对应的 tick 曲线。
	 * <p>
	 * 原版调试图表是 toggle 语义，因此这里必须先读当前状态，只在未开启时补开。
	 * </p>
	 */
	private static void ensureTickChartVisible(Minecraft client) {
		if (!config.openTickChart()) {
			return;
		}

		DebugScreenOverlay debugOverlay = client.getDebugOverlay();
		if (!debugOverlay.showDebugScreen()) {
			debugOverlay.toggleOverlay();
		}
		if (!debugOverlay.showFpsCharts()) {
			debugOverlay.toggleFpsCharts();
		}
		RedstoneLink.LOGGER.info(
			"Bench client automation enabled tick chart after join. player={} server={}",
			config.playerName(),
			config.serverAddress()
		);
	}

	/**
	 * 客户端已经进入世界后，自动关闭 bench 启动链路遗留的临时界面。
	 * <p>
	 * 这里只收起标题页、多人页、连接页和断线页，避免误关玩家主动打开的游戏内界面。
	 * </p>
	 */
	private static void dismissTransientStartupScreen(Minecraft client) {
		Screen currentScreen = client.screen;
		if (!isTransientStartupScreen(currentScreen)) {
			return;
		}

		RedstoneLink.LOGGER.info(
			"Bench client automation dismissing startup screen after join. player={} screen={}",
			config.playerName(),
			currentScreen.getClass().getSimpleName()
		);
		client.setScreen(null);
	}

	/**
	 * 仅识别 bench 自动连接链路可能残留的临时界面。
	 */
	private static boolean isTransientStartupScreen(Screen screen) {
		if (screen == null) {
			return false;
		}
		return screen instanceof TitleScreen
			|| screen instanceof JoinMultiplayerScreen
			|| screen instanceof ConnectScreen
			|| screen instanceof ReceivingLevelScreen
			|| screen instanceof LevelLoadingScreen
			|| screen instanceof ProgressScreen
			|| screen instanceof DisconnectedScreen;
	}

	private static Screen buildParentScreen(Minecraft client) {
		if (client.screen instanceof DisconnectedScreen disconnectedScreen) {
			return disconnectedScreen;
		}
		if (client.screen != null) {
			return client.screen;
		}
		return new JoinMultiplayerScreen(new TitleScreen());
	}

	/**
	 * 返回最近一次自动连接尝试时间，便于后续扩展调试。
	 */
	public static long getLastConnectAttemptAtMs() {
		return lastConnectAttemptAtMs;
	}
}
