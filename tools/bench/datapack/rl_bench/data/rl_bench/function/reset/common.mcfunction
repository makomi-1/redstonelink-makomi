# RedstoneLink bench common reset
# 仅回收 bench 通用运行态；具体场地清空仍由外部脚本按 case 边界执行。

function rl_bench:helper/common/reset_scoreboards
function rl_bench:helper/common/cleanup_runtime_entities
function rl_bench:assert/state/noop
function rl_bench:assert/trace/noop
