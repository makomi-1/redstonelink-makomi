package com.makomi.api.v1;

import com.makomi.api.v1.adapter.AdapterRegistryApi;
import com.makomi.api.v1.internal.RedstoneLinkApiImpl;
import com.makomi.api.v1.service.LinkGraphApi;
import com.makomi.api.v1.service.QueryApi;
import com.makomi.api.v1.service.TriggerApi;

/**
 * RedstoneLink 对外 API 入口（v1）。
 */
public final class RedstoneLinkApi {
	public static final int API_VERSION = 1;
	private static final RedstoneLinkApiImpl IMPL = new RedstoneLinkApiImpl();

	private RedstoneLinkApi() {
	}

	/**
	 * 链路图读写能力。
	 */
	public static LinkGraphApi graph() {
		return IMPL;
	}

	/**
	 * 触发投递能力。
	 */
	public static TriggerApi trigger() {
		return IMPL;
	}

	/**
	 * 只读查询能力。
	 */
	public static QueryApi query() {
		return IMPL;
	}

	/**
	 * 外部适配器注册能力。
	 */
	public static AdapterRegistryApi adapters() {
		return IMPL;
	}
}

