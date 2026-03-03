package com.makomi.api.v1.adapter;

import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/**
 * 外部适配器注册表 API。
 */
public interface AdapterRegistryApi {
	/**
	 * 注册外部目标适配器。
	 */
	void registerTargetAdapter(ResourceLocation id, ExternalTargetAdapter adapter);

	/**
	 * 注销外部目标适配器。
	 */
	void unregisterTargetAdapter(ResourceLocation id);

	/**
	 * 按 id 查询外部目标适配器。
	 */
	Optional<ExternalTargetAdapter> findTargetAdapter(ResourceLocation id);

	/**
	 * 获取已注册的外部目标适配器 id 列表。
	 */
	Set<ResourceLocation> getTargetAdapterIds();

	/**
	 * 注册外部触发器适配器。
	 */
	void registerTriggerAdapter(ResourceLocation id, ExternalTriggerAdapter adapter);

	/**
	 * 注销外部触发器适配器。
	 */
	void unregisterTriggerAdapter(ResourceLocation id);

	/**
	 * 按 id 查询外部触发器适配器。
	 */
	Optional<ExternalTriggerAdapter> findTriggerAdapter(ResourceLocation id);

	/**
	 * 获取已注册的外部触发器适配器 id 列表。
	 */
	Set<ResourceLocation> getTriggerAdapterIds();
}

