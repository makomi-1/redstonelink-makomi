package com.makomi.data;

import java.lang.reflect.Field;
import net.minecraft.server.MinecraftServer;
import sun.misc.Unsafe;

/**
 * 为需要非空服务端实例的单元测试提供最小 dummy server。
 */
final class TestMinecraftServerFactory {
	private TestMinecraftServerFactory() {
	}

	/**
	 * 分配一个未初始化的最小服务端对象，仅用于触发线程边界断言。
	 */
	static MinecraftServer newDummyServer() {
		try {
			return (MinecraftServer) unsafe().allocateInstance(resolveConcreteServerClass());
		} catch (ReflectiveOperationException ex) {
			throw new IllegalStateException("failed to allocate dummy MinecraftServer", ex);
		}
	}

	private static Class<?> resolveConcreteServerClass() throws ClassNotFoundException {
		try {
			return Class.forName("net.minecraft.server.dedicated.DedicatedServer");
		} catch (ClassNotFoundException ex) {
			return MinecraftServer.class;
		}
	}

	private static Unsafe unsafe() throws ReflectiveOperationException {
		Field field = Unsafe.class.getDeclaredField("theUnsafe");
		field.setAccessible(true);
		return (Unsafe) field.get(null);
	}
}
