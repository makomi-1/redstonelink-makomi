package com.makomi.network;

import com.makomi.RedstoneLink;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * bench 真实玩家命令执行网络通道。
 * <p>
 * 该通道只负责最小 payload 契约和统一注册入口，具体注册与服务端处理分别下沉到 helper。
 * </p>
 */
public final class BenchCommandNetwork {
	private BenchCommandNetwork() {
	}

	/**
	 * 注册 bench 命令桥全部 payload 与接包器。
	 */
	public static void register() {
		BenchCommandNetworkRegistrationSupport.register();
	}

	/**
	 * bench 客户端发起的“按真实玩家命令源执行”请求。
	 */
	public record ExecutePlayerCommandPayload(long requestId, String command, boolean captureTickWindow) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<ExecutePlayerCommandPayload> TYPE = new CustomPacketPayload.Type<>(
			Identifier.fromNamespaceAndPath(RedstoneLink.MOD_ID, "bench_execute_player_command")
		);
		public static final StreamCodec<FriendlyByteBuf, ExecutePlayerCommandPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> {
				buffer.writeLong(payload.requestId());
				buffer.writeUtf(payload.command());
				buffer.writeBoolean(payload.captureTickWindow());
			},
			buffer -> new ExecutePlayerCommandPayload(buffer.readLong(), buffer.readUtf(), buffer.readBoolean())
		);

		public ExecutePlayerCommandPayload {
			command = command == null ? "" : command.trim();
			if (command.startsWith("/")) {
				command = command.substring(1).trim();
			}
		}

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 服务端回传给 bench 客户端的结构化命令执行结果。
	 */
	public record PlayerCommandResultPayload(
		long requestId,
		boolean callbackSuccess,
		int resultCode,
		String output,
		boolean hasTickWindow,
		long tickWindowStart,
		long tickWindowEnd,
		String errorDetail
	) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<PlayerCommandResultPayload> TYPE = new CustomPacketPayload.Type<>(
			Identifier.fromNamespaceAndPath(RedstoneLink.MOD_ID, "bench_player_command_result")
		);
		public static final StreamCodec<FriendlyByteBuf, PlayerCommandResultPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> {
				buffer.writeLong(payload.requestId());
				buffer.writeBoolean(payload.callbackSuccess());
				buffer.writeInt(payload.resultCode());
				buffer.writeUtf(payload.output());
				buffer.writeBoolean(payload.hasTickWindow());
				buffer.writeLong(payload.tickWindowStart());
				buffer.writeLong(payload.tickWindowEnd());
				buffer.writeUtf(payload.errorDetail());
			},
			buffer -> new PlayerCommandResultPayload(
				buffer.readLong(),
				buffer.readBoolean(),
				buffer.readInt(),
				buffer.readUtf(),
				buffer.readBoolean(),
				buffer.readLong(),
				buffer.readLong(),
				buffer.readUtf()
			)
		);

		public PlayerCommandResultPayload {
			output = output == null ? "" : output;
			errorDetail = errorDetail == null ? "" : errorDetail;
		}

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}
}
