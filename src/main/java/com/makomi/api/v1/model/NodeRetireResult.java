package com.makomi.api.v1.model;

/**
 * 节点退役结果。
 */
public record NodeRetireResult(boolean nodeRemoved, int linksRemoved, boolean retiredMarked) {}

