# 开发与测试常用命令

## 1. 构建

```powershell
./gradlew --no-daemon compileJava remapJar
```

## 2. 带锂构建

```powershell
./gradlew -PwithLithium=true --no-daemon compileJava remapJar
```

## 3. 本地启动（带锂 + 调试命中日志）

```powershell
./gradlew "-Dredstonelink.debug.lithium.mixin=true" -PwithLithium=true --no-daemon runClient
```

## 4. 双矩阵脚本（Windows）

```powershell
./tools/ci/run_lithium_matrix.ps1
```

## 5. 双矩阵脚本（含 runClient 烟测）

```powershell
./tools/ci/run_lithium_matrix.ps1 -RunClient
```

## 6. 查看 Gradle 弃用来源

```powershell
./gradlew --no-daemon --warning-mode all help
```

## 7. 包哈希比对（定位外部误包）

```powershell
Get-FileHash -Algorithm SHA256 build/libs/redstonelink-fabric-1.21.1-1.0.0-alpha.jar
```

## 8. 外部日志快速检索（示例）

```powershell
Select-String -Path "D:\newthing\22\.minecraft\versions\1.21.1-Fabric 0.18.4-mods-test\logs\latest.log" -Pattern "RedstoneLink/MixinRoute|RedstoneLink/Diag|RL-LI-"
```

## 9. 官方源兜底构建（当国内镜像不稳定时）

```powershell
./gradlew --no-daemon \
	-Ploom_resources_base=https://resources.download.minecraft.net/ \
	-Ploom_version_manifests=https://launchermeta.mojang.com/mc/game/version_manifest_v2.json \
	help
```
