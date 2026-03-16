package com.makomi.compat;

import com.makomi.RedstoneLink;
import com.makomi.config.RedstoneLinkConfig;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RedStoneWireBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * lithium 兼容健康检查与诊断输出。
 * <p>
 * 目标：
 * 1. 在启动期尽早识别“关键动态方法缺失”；
 * 2. 在命令诊断中输出签名校验异常码，降低静默失效定位成本。
 * </p>
 */
public final class LithiumCompatHealth {
	/**
	 * 动态方法缺失（高风险，可能导致 require=0 静默跳过）。
	 */
	public static final String CODE_DYNAMIC_METHOD_MISSING = "RL-LI-001";
	private static final Logger LOGGER = LoggerFactory.getLogger(RedstoneLink.MOD_ID + "/lithium-health");
	private static final List<MethodSignatureCheck> REQUIRED_DYNAMIC_SIGNATURES = List.of(
		new MethodSignatureCheck("getReceivedPower(Level, BlockPos) -> int", int.class, Level.class, BlockPos.class),
		new MethodSignatureCheck("getPowerFromSide(Level, BlockPos, Direction, boolean) -> int", int.class, Level.class, BlockPos.class, Direction.class, boolean.class),
		new MethodSignatureCheck("getStrongPowerTo(Level, BlockPos, Direction) -> int", int.class, Level.class, BlockPos.class, Direction.class)
	);
	private static volatile boolean startupChecked;

	private LithiumCompatHealth() {
	}

	/**
	 * 启动期执行一次健康检查。
	 * <p>
	 * 若开启 strict 模式且发现关键异常，将直接抛错中止，避免静默带病运行。
	 * </p>
	 */
	public static void validateOnInitialize() {
		if (startupChecked) {
			return;
		}
		startupChecked = true;

		LithiumDiagSnapshot snapshot = snapshot();
		if (!snapshot.lithiumLoaded()) {
			return;
		}

		if (CODE_DYNAMIC_METHOD_MISSING.equals(snapshot.anomalyCode())) {
			String message = "[" + CODE_DYNAMIC_METHOD_MISSING + "] "
				+ "Missing lithium dynamic signatures on RedStoneWireBlock: "
				+ String.join(", ", snapshot.missingDynamicSignatures());
			if (snapshot.strictMode()) {
				throw new IllegalStateException(message);
			}
			LOGGER.error(message);
			return;
		}

		LOGGER.info(
			"[RedstoneLink/LithiumHealth] lithiumLoaded=true, strictMode={}, dynamicMethodsOk=true",
			snapshot.strictMode()
		);
	}

	/**
	 * 采集当前 lithium 兼容诊断快照。
	 */
	public static LithiumDiagSnapshot snapshot() {
		boolean lithiumLoaded = FabricLoader.getInstance().isModLoaded("lithium");
		boolean strictMode = RedstoneLinkConfig.lithiumStrictMode();
		List<String> missingDynamicSignatures = lithiumLoaded ? findMissingDynamicSignatures() : List.of();
		String anomalyCode = resolveAnomalyCode(lithiumLoaded, missingDynamicSignatures);
		String anomalyMessage = resolveAnomalyMessage(anomalyCode);
		return new LithiumDiagSnapshot(
			lithiumLoaded,
			strictMode,
			missingDynamicSignatures,
			anomalyCode,
			anomalyMessage
		);
	}

	/**
	 * 在 RedStoneWireBlock 上按签名检查 lithium 动态方法是否存在。
	 */
	private static List<String> findMissingDynamicSignatures() {
		List<Method> methods = new ArrayList<>();
		for (Method method : RedStoneWireBlock.class.getDeclaredMethods()) {
			methods.add(method);
		}

		List<String> missing = new ArrayList<>();
		for (MethodSignatureCheck signature : REQUIRED_DYNAMIC_SIGNATURES) {
			boolean found = false;
			for (Method method : methods) {
				if (signature.matches(method)) {
					found = true;
					break;
				}
			}
			if (!found) {
				missing.add(signature.label());
			}
		}
		return List.copyOf(missing);
	}

	/**
	 * 推导当前异常码。
	 */
	private static String resolveAnomalyCode(boolean lithiumLoaded, List<String> missingDynamicSignatures) {
		if (!lithiumLoaded) {
			return null;
		}
		if (!missingDynamicSignatures.isEmpty()) {
			return CODE_DYNAMIC_METHOD_MISSING;
		}
		return null;
	}

	/**
	 * 将异常码映射为可读说明。
	 */
	private static String resolveAnomalyMessage(String anomalyCode) {
		if (anomalyCode == null) {
			return null;
		}
		return switch (anomalyCode) {
			case CODE_DYNAMIC_METHOD_MISSING ->
				"关键动态方法缺失，可能因签名变化导致注入静默失效。";
			default -> "未知兼容异常。";
		};
	}

	/**
	 * lithium 兼容诊断快照。
	 */
	public record LithiumDiagSnapshot(
		boolean lithiumLoaded,
		boolean strictMode,
		List<String> missingDynamicSignatures,
		String anomalyCode,
		String anomalyMessage
	) {
		/**
		 * @return 一行摘要，适合命令与日志输出
		 */
		public String toSummaryLine() {
			return "lithiumLoaded="
				+ lithiumLoaded
				+ ", strictMode="
				+ strictMode
				+ ", missingDynamicSignatures="
				+ missingDynamicSignatures
				+ ", anomalyCode="
				+ (anomalyCode == null ? "-" : anomalyCode);
		}
	}

	/**
	 * 方法签名检查器：按返回值与参数类型匹配，避免受映射名变化影响。
	 */
	private record MethodSignatureCheck(String label, Class<?> returnType, Class<?>... parameterTypes) {
		private boolean matches(Method method) {
			if (!method.getReturnType().equals(returnType)) {
				return false;
			}
			Class<?>[] actual = method.getParameterTypes();
			if (actual.length != parameterTypes.length) {
				return false;
			}
			for (int i = 0; i < actual.length; i++) {
				if (!actual[i].equals(parameterTypes[i])) {
					return false;
				}
			}
			return true;
		}
	}
}
