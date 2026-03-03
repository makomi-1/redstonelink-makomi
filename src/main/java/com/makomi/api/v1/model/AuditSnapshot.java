package com.makomi.api.v1.model;

/**
 * 链路审计快照。
 */
public record AuditSnapshot(
	int onlineCoreNodes,
	int onlineButtonNodes,
	int totalLinks,
	int linksWithMissingEndpoint,
	int linkedButtonSerialCount,
	int linkedCoreSerialCount
) {}

