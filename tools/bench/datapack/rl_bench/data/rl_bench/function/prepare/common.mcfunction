# RedstoneLink bench common prepare
# 统一关闭随机干扰，保持场景稳定。

gamerule doDaylightCycle false
gamerule doWeatherCycle false
gamerule doMobSpawning false
gamerule randomTickSpeed 0
gamerule commandBlockOutput false
gamerule sendCommandFeedback true
time set day
weather clear 999999
kill @e[type=item,distance=..128]
kill @e[type=experience_orb,distance=..128]
kill @e[type=armor_stand,tag=rl_bench]
