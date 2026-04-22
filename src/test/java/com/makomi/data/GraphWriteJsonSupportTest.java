package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * graph 保存 JSON 协议稳定契约测试。
 */
@Tag("stable-core")
class GraphWriteJsonSupportTest {
	/**
	 * 请求解析应归一化 mode、别名、频道和 `triggerSource -> core` 覆盖目标集合。
	 */
	@Test
	void parseRequestShouldNormalizeSupportedOperations() {
		String rawJson = """
			{
			  "draftId": " draft-1 ",
			  "baseSnapshotId": " snapshot-1 ",
			  "mode": " CHANNEL ",
			  "baseGraphRevision": 17,
			  "operations": [
			    {
			      "type": "RenameNodeAlias",
			      "nodeType": "triggerSource",
			      "serial": 12,
			      "alias": "  alpha  "
			    },
			    {
			      "type": "ReplaceTriggerSourceTargets",
			      "triggerSourceSerial": 12,
			      "expectedSourceRevision": 5,
			      "targetCoreSerials": [8, 0, 8, 3]
			    },
			    {
			      "type": "SetNodeChannel",
			      "nodeType": "core",
			      "serial": 7,
			      "expectedSourceRevision": 0,
			      "expectedCoreRevision": 9,
			      "channel": 23
			    }
			  ]
			}
			""";

		GraphWriteJsonSupport.ParseResult parseResult = GraphWriteJsonSupport.parseRequest(rawJson);

		assertTrue(parseResult.successful());
		assertNotNull(parseResult.request());
		assertEquals("draft-1", parseResult.request().draftId());
		assertEquals("snapshot-1", parseResult.request().baseSnapshotId());
		assertEquals("channel", parseResult.request().mode());
		assertEquals(17L, parseResult.request().baseGraphRevision());
		assertEquals(3, parseResult.request().operations().size());

		GraphWriteJsonSupport.RenameNodeAliasOperation renameOperation =
			(GraphWriteJsonSupport.RenameNodeAliasOperation) parseResult.request().operations().get(0);
		assertEquals(LinkNodeType.TRIGGER_SOURCE, renameOperation.nodeType());
		assertEquals(12L, renameOperation.serial());
		assertEquals("alpha", renameOperation.alias());
		assertEquals("triggerSource:12", renameOperation.nodeKey());

		GraphWriteJsonSupport.ReplaceTriggerSourceTargetsOperation replaceOperation =
			(GraphWriteJsonSupport.ReplaceTriggerSourceTargetsOperation) parseResult.request().operations().get(1);
		assertEquals(12L, replaceOperation.triggerSourceSerial());
		assertEquals(5L, replaceOperation.expectedSourceRevision());
		assertEquals(List.of(8L, 3L), replaceOperation.targetCoreSerials());

		GraphWriteJsonSupport.SetNodeChannelOperation channelOperation =
			(GraphWriteJsonSupport.SetNodeChannelOperation) parseResult.request().operations().get(2);
		assertEquals(LinkNodeType.CORE, channelOperation.nodeType());
		assertEquals(7L, channelOperation.serial());
		assertEquals(9L, channelOperation.expectedCoreRevision());
		assertEquals(23L, channelOperation.channel());
		assertEquals("core:7", channelOperation.nodeKey());
	}

	/**
	 * 非法或不支持的操作类型应直接收口为 rejected 返回体。
	 */
	@Test
	void parseRequestShouldRejectUnsupportedOperation() {
		GraphWriteJsonSupport.ParseResult parseResult = GraphWriteJsonSupport.parseRequest("""
			{
			  "mode": "serial",
			  "operations": [
			    {
			      "type": "UnsupportedOperation"
			    }
			  ]
			}
			""");

		assertFalse(parseResult.successful());
		JsonObject response = JsonParser.parseString(parseResult.failureResponseJson()).getAsJsonObject();
		assertEquals("ok", response.get("status").getAsString());
		assertEquals("rejected", response.get("result").getAsString());
		assertEquals("unsupported_operation", response.get("reason").getAsString());
	}

	/**
	 * applied 回包应收敛为最小摘要字段，避免大批量保存时响应体膨胀。
	 */
	@Test
	void buildAppliedResponseShouldExposeSummaryFields() {
		String responseJson = GraphWriteJsonSupport.buildAppliedResponse(
			"已保存。",
			21L,
			3,
			true
		);

		JsonObject response = JsonParser.parseString(responseJson).getAsJsonObject();

		assertEquals("applied", response.get("result").getAsString());
		assertEquals(21L, response.get("graphRevision").getAsLong());
		assertEquals(3, response.get("changedNodeCount").getAsInt());
		assertTrue(response.get("refreshRequired").getAsBoolean());
		assertFalse(response.has("updatedNodes"));
	}

	/**
	 * preview 回包应完整暴露 graph 保存成本与窗口判定字段。
	 */
	@Test
	void buildPreviewResponseShouldExposePreviewState() {
		String responseJson = GraphWriteJsonSupport.buildPreviewResponse(
			"当前可保存。",
			33L,
			new GraphWriteJsonSupport.PreviewState(2, 5, 9, true, false, false, true, 0L, 12L, false)
		);

		JsonObject response = JsonParser.parseString(responseJson).getAsJsonObject();
		JsonObject preview = response.getAsJsonObject("preview");

		assertEquals("preview", response.get("result").getAsString());
		assertEquals("preview", response.get("reason").getAsString());
		assertEquals(33L, response.get("graphRevision").getAsLong());
		assertEquals(2, preview.get("aliasCost").getAsInt());
		assertEquals(5, preview.get("graphCost").getAsInt());
		assertEquals(9, preview.get("graphWriteUnitCount").getAsInt());
		assertTrue(preview.get("aliasAllowed").getAsBoolean());
		assertFalse(preview.get("graphAllowed").getAsBoolean());
		assertTrue(preview.get("graphHardBlocked").getAsBoolean());
		assertEquals(12L, preview.get("graphWaitTicks").getAsLong());
		assertFalse(preview.get("canSave").getAsBoolean());
	}

	/**
	 * updatedNodes 去重应按 `nodeKey` 保留最后一次状态，便于服务端构建最小补丁。
	 */
	@Test
	void dedupeUpdatedNodesShouldKeepLastStatePerNodeKey() {
		List<GraphWriteJsonSupport.UpdatedNodeState> deduped = GraphWriteJsonSupport.dedupeUpdatedNodes(
			List.of(
				new GraphWriteJsonSupport.UpdatedNodeState(
					"core:8",
					LinkNodeType.CORE,
					8L,
					"old",
					"old(#8)",
					"serial",
					0L,
					0L,
					3L
				),
				new GraphWriteJsonSupport.UpdatedNodeState(
					"core:8",
					LinkNodeType.CORE,
					8L,
					"new",
					"new(#8)",
					"channel",
					19L,
					0L,
					4L
				)
			)
		);

		assertEquals(1, deduped.size());
		assertEquals("new", deduped.get(0).alias());
		assertEquals("channel", deduped.get(0).connectionMode());
		assertEquals(19L, deduped.get(0).channel());
		assertEquals(4L, deduped.get(0).coreRevision());
	}

	/**
	 * conflict / rejected 回包应保留原因与 updatedNodes 列表，供前端统一消费。
	 */
	@Test
	void buildConflictAndRejectedResponseShouldKeepReasonAndUpdatedNodes() {
		String conflictJson = GraphWriteJsonSupport.buildConflictResponse(
			"source_revision_conflict",
			"保存冲突",
			44L,
			List.of(
				new GraphWriteJsonSupport.UpdatedNodeState(
					"triggerSource:4",
					LinkNodeType.TRIGGER_SOURCE,
					4L,
					"",
					"4",
					"serial",
					0L,
					9L,
					0L
				)
			)
		);
		String rejectedJson = GraphWriteJsonSupport.buildRejectedResponse(
			"rate_limit_exceeded",
			"保存过于频繁",
			45L,
			List.of()
		);

		JsonObject conflict = JsonParser.parseString(conflictJson).getAsJsonObject();
		JsonObject rejected = JsonParser.parseString(rejectedJson).getAsJsonObject();
		JsonArray conflictUpdatedNodes = conflict.getAsJsonArray("updatedNodes");

		assertEquals("conflict", conflict.get("result").getAsString());
		assertEquals("source_revision_conflict", conflict.get("reason").getAsString());
		assertEquals(1, conflictUpdatedNodes.size());
		assertEquals("rejected", rejected.get("result").getAsString());
		assertEquals("rate_limit_exceeded", rejected.get("reason").getAsString());
		assertEquals(0, rejected.getAsJsonArray("updatedNodes").size());
	}
}
