# Pentax K-1 Sync v6.0.0 (终极全功能版)

专为 **PENTAX K-1（一代）** 打造的原生 Android 图传、无线遥控与 SpektraLab 81 波长物理暗房客户端。

## v6.0.0 全部新增与优化
1. **自动回写原图 EXIF**：所有导出的调色图与宾得水印照均自动回写拍摄时间、机身、镜头、焦距、光圈、快门与 ISO，彻底保持手机相册时间线准确。
2. **长按预览对比原图（Before / After）**：在「后期」预览画布上按住即可即时对比原图，松手恢复效果。
3. **双通道 LibRaw 36.4MP RAW 加速 & 真白平衡**：实时预览自动走 LibRaw 半尺寸（~9MP，内存降 75%、提速 4 倍），导出时自动切回全尺寸 36.4MP；原生接入 Adobe DNG 色温/色调白平衡求解。
4. **全面解锁 SpektraLab 81 波长内核**：
   - 动态加载**全部 121 款实测胶片**（支持品牌/名称/ISO 实时搜索）
   - 解锁**光学黑柔/白柔/星光/电影柔光滤镜（Black Pro-Mist / Glimmerglass / CineBloom）**与**物理漏光（Light Leak）**
   - 解锁**大光比场景置位（Scene Latitude）**一键高光拉回与暗部提拉
   - 支持**暗房配方收藏**与**Spektra 暗房批量出片**
5. **双图并排同步放大对比**：在图库勾选 2 张照片点击「对比」，双指缩放任意一张时两图同步放大平移，一眼挑出最锐的一张。
6. **真机联调抓包日志 & 个人配置备份恢复**：支持一键导出/恢复星标、镜头预设与暗房配方 JSON；内置 30 条 K-1 协议抓包日志一键复制；精简未使用的 6.6MB minilab 体积。

## 配置固定 APK 签名（可选，以后新版可直接覆盖安装不丢数据）
在你的电脑执行一次生成个人密钥库：
```bash
keytool -genkey -v -keystore release.keystore -alias k1sync -keyalg RSA -keysize 2048 -validity 10000
base64 -w 0 release.keystore > keystore.b64
```
然后在 GitHub 仓库的 **Settings → Secrets and variables → Actions** 中添加 4 个 Secret：
- `SIGNING_KEYSTORE_BASE64`（粘贴 `keystore.b64` 内容）
- `SIGNING_STORE_PASSWORD`（密钥库密码）
- `SIGNING_KEY_ALIAS`（`k1sync`）
- `SIGNING_KEY_PASSWORD`（密钥密码）

不配置也能正常编译（自动回退默认签名，并可用 App 内的「备份/恢复个人配置」保留数据）。
