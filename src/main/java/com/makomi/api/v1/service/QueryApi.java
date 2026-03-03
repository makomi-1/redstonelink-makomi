package com.makomi.api.v1.service;

import com.makomi.api.v1.model.ApiNodeType;
import com.makomi.api.v1.model.AuditSnapshot;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;

/**
 * 只读查询 API。
 */
public interface QueryApi {
	/**
	 * 获取当前链路审计快照。
	 */
	AuditSnapshot getAuditSnapshot(ServerLevel level);

	/**
	 * 获取某类节点的活跃序号集合。
	 */
	Set<Long> getActiveSerials(ServerLevel level, ApiNodeType nodeType);

	/**
	 * 获取某类节点的退役序号集合。
	 */
	Set<Long> getRetiredSerials(ServerLevel level, ApiNodeType nodeType);
}

