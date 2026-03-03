package com.makomi.api.v1.service;

import com.makomi.api.v1.model.ActorContext;
import com.makomi.api.v1.model.ApiNodeType;
import com.makomi.api.v1.model.LinkMutationResult;
import com.makomi.api.v1.model.NodeRetireResult;
import com.makomi.api.v1.model.NodeSnapshot;
import java.util.Optional;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;

/**
 * 链路图读写 API。
 */
public interface LinkGraphApi {
	/**
	 * 查询节点快照。
	 */
	Optional<NodeSnapshot> findNode(ServerLevel level, ApiNodeType nodeType, long serial);

	/**
	 * 查询某个源节点当前链接的目标序号集合。
	 */
	Set<Long> getLinkedTargets(ServerLevel level, ApiNodeType sourceType, long sourceSerial);

	/**
	 * 以“替换语义”设置源节点的目标集合。
	 */
	LinkMutationResult setLinks(
		ServerLevel level,
		ApiNodeType sourceType,
		long sourceSerial,
		Set<Long> targets,
		ActorContext actorContext
	);

	/**
	 * 切换单个目标链接（已存在则移除，不存在则添加）。
	 */
	LinkMutationResult toggleLink(
		ServerLevel level,
		ApiNodeType sourceType,
		long sourceSerial,
		long targetSerial,
		ActorContext actorContext
	);

	/**
	 * 清空源节点的所有链接。
	 */
	LinkMutationResult clearLinks(
		ServerLevel level,
		ApiNodeType sourceType,
		long sourceSerial,
		ActorContext actorContext
	);

	/**
	 * 退役节点并清理其关联链接。
	 */
	NodeRetireResult retireNode(ServerLevel level, ApiNodeType nodeType, long serial, ActorContext actorContext);
}

