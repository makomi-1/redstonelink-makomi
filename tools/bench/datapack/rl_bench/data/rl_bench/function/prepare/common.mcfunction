# RedstoneLink bench common prepare
# 调用可复用子函数，统一关闭干扰并清理运行时残留。

function rl_bench:fixture/clear/stabilize_world
function rl_bench:fixture/player/reset_nearby_players
function rl_bench:helper/common/cleanup_runtime_entities
