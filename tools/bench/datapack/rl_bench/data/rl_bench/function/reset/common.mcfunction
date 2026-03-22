# RedstoneLink bench common reset
# 仅清理 bench 常见运行时残留；具体场地清空由外部脚本按 case 边界执行。

scoreboard players set #state rl_bench.state 0
scoreboard players set #tick rl_bench.tick 0
scoreboard players set #case rl_bench.case 0
kill @e[type=item,distance=..128]
kill @e[type=experience_orb,distance=..128]
kill @e[type=armor_stand,tag=rl_bench]
