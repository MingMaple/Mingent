# signing/ —— 本机签名配置

这个目录只放**你自己的**签名信息。整个目录被 `.gitignore` 忽略，仓库里只会提交本文件；keystore 实体也**不放这里**，放在仓库外的兄弟目录 `../MG_Keys/`。

## 需要准备的东西

1. **keystore 实体**：`<仓库根>/../MG_Keys/mingent.jks`（仓库外的兄弟目录）。

```bash
keytool -genkeypair -v -storetype PKCS12 -keystore ../MG_Keys/mingent.jks \
  -alias mingmaple -keyalg RSA -keysize 4096 -sigalg SHA256withRSA \
  -validity 10950 -dname "CN=MingMaple, OU=Mingent, O=MingMaple, C=CN"
```

2. **同目录的 `keystore.properties`**：

```properties
# keystore 路径（相对仓库根目录）
storeFile=../MG_Keys/mingent.jks
# 密码可以不写：改用环境变量 MINGENT_STORE_PASSWORD / MINGENT_KEY_PASSWORD（优先于本文件）
storePassword=你的库密码
keyAlias=mingmaple
keyPassword=你的密钥密码
```

## 缺了会怎样

- `keystore.properties` 不存在，或其中的 `storeFile` 指向的文件不存在 ⇒ 构建**自动回落到 debug 签名**，并在日志里打一行 warn（「未找到 … 本次构建使用 debug 签名，产物不能直接发布。」）。见到这行 warn 就当失败处理。
- `storePassword` / `keyPassword` 给错 ⇒ 在签名阶段直接失败，不会静默降级。

## 一定要备份

keystore 与密码**丢了就再也发不出同签名的更新**：Android 只允许同签名的包覆盖安装，签名变了用户必须先卸载（本地数据一起没）。请把 keystore 与参数单独存一份到仓库之外（加密备份盘 / 密码管理器），并在项目运行规则 `RULES.md` §1 与 §4 登记指纹。
