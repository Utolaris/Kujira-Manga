# 发布签名

Release 变体的签名密钥**不入库**：密钥库文件与密码分开存放，密码只在本机 macOS 钥匙串里。

## 一眼看懂

| 项 | 值 |
|---|---|
| 钥匙串条目（service） | **`Kujira-Manga-Key`** |
| 钥匙串账号（account） | `Utolaris` |
| 钥匙串里存的是什么 | 密钥库的 `storePassword`（同一密码也用作 `keyPassword`） |
| 密钥库文件 | `release-key/Kujira-Manga-Key.p12`（PKCS#12，已被 `.gitignore` 忽略） |
| 密钥库别名（alias） | `Kujira-Manga-Key` |
| 证书主体 | `CN=Kujira-Manga, OU=Release, O=Utolaris`，RSA 4096 |
| 环境变量 | `KUJIRA_MANGA_RELEASE_STORE_PASSWORD` / `KUJIRA_MANGA_RELEASE_KEY_PASSWORD` |

> 旧的 `jmcomic-plus-release-signing` 钥匙串条目与 `release-key/jmcomic-plus-release.p12`
> 已在 v1.4.4 更名时删除，不要再去读它们。

## 取密码

`storePassword` 与 `keyPassword` 是同一个值，所以只存了一条钥匙串记录。统一走 CLI：

```bash
eval "$(./scripts/android signing-env)"
```

它等价于：

```bash
security find-generic-password -s Kujira-Manga-Key -a Utolaris -w
```

需要人工核对条目是否存在（不读密码）：

```bash
security find-generic-password -s Kujira-Manga-Key
security find-generic-password -s Kujira-Manga-Key -w   # 会弹钥匙串授权
```

## 打 Release 包

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
eval "$(./scripts/android signing-env)"
./gradlew :app:assembleRelease --console=plain
```

产物落在 `app/build/outputs/apk/release/kujira-manga_v<版本>_<git短哈希>.apk`。

校验包名、版本与签名：

```bash
./scripts/android apk-info app/build/outputs/apk/release/*.apk
./scripts/android apk-sign app/build/outputs/apk/release/*.apk
```

期望：包名 `kujira.manga`、`versionName 1.4.4`、证书主体 `CN=JMcomic Plus`、v2 方案通过。
若 `apk-sign` 报「未签名」或证书是 debug 证书，说明环境变量没注入成功，
Gradle 会静默产出未签名 APK —— 别急着上传。

确认签名身份没被意外换掉：

```bash
./scripts/android apk-sign app/build/outputs/apk/release/*.apk | grep -i "SHA-256"
# 应包含 FA:6E:79:FF:EF:64:98:E8:E9:BD:23:1A:A4:22:79:8C:BC:1D:F5:8F:F5:07:7A:C1:A5:2A:10:75:81:AF:C9:DA
```

## 轮换 / 重建密钥

```bash
keytool -genkeypair -v \
  -keystore release-key/Kujira-Manga-Key.p12 -storetype PKCS12 \
  -alias Kujira-Manga-Key -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Kujira-Manga, OU=Release, O=Utolaris"

security add-generic-password -U -a Utolaris -s Kujira-Manga-Key -w '<新密码>'
```

沿用旧证书、只换文件名与别名时用 `keytool -importkeystore` 指定
`-srcalias` / `-destalias` 重写一遍，签名身份不变。

> **换密钥 = 换签名身份**：即使包名不变，系统也会拒绝覆盖安装，用户必须卸载重装且数据不保留。
> 只有在明确接受这个代价时才重建密钥对。
