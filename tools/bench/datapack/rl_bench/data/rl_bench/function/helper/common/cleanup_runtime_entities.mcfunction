# 清理 bench 运行期常见残留实体，避免样本被掉落物和临时标记污染。

kill @e[type=item,distance=..128]
kill @e[type=experience_orb,distance=..128]
kill @e[type=armor_stand,tag=rl_bench]
