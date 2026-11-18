# Smartisan Calendar 反编译 / 二次开发工作流
#
# 所有工具调用都指向本仓库固定版本，不污染全局。
# 直接运行 `make help` 查看可用目标。

JAVA      := /opt/homebrew/opt/openjdk/bin/java
JADX      := jadx
APKTOOL   := $(JAVA) -jar Tool/apktool.jar
SIGNER    := $(JAVA) -jar Tool/uber-apk-signer.jar
FW_DIR    := .apktool-framework
APK       := Calendar_8.1.2.apk
CAL_DIR   := apktool/calendar
JADX_DIR  := decompiled/calendar
DIST      := dist
PKG       := com.android.calendar

.PHONY: help env install-framework decode decode-jadx build encode sign all install pull-log clean

help:
	@echo "可用目标："
	@echo "  make env          检查 Java / 工具版本"
	@echo "  make decode       apktool 反编译 -> apktool/calendar"
	@echo "  make decode-jadx  jadx 反编译 Java 源码 -> decompiled/calendar"
	@echo "  make build        编译 src/ 的 Java 并合入 smali（javac->d8->baksmali）"
	@echo "  make sign         回编译 + zipalign + v2/v3 签名 -> dist/Calendar_debug_signed.apk"
	@echo "  make all          build + sign"
	@echo "  make install      安装到已连接设备（adb install -r）"
	@echo "  make pull-log     从设备拉取调试日志到 dist/cal_dbg.log"
	@echo "  make clean        清理反编译产物"

env:
	@echo "Java:"; $(JAVA) -version 2>&1 | head -1
	@echo "apktool.jar:"; $(APKTOOL) --version 2>&1 | head -1
	@echo "jadx:"; $(JADX) --version 2>&1 | head -1
	@echo "uber-apk-signer:"; $(SIGNER) --version 2>&1 | head -1

install-framework:
	@mkdir -p $(FW_DIR)
	$(APKTOOL) if Tool/framework-res.apk -p $(FW_DIR)
	$(APKTOOL) if Tool/smartisanos_11.apk -p $(FW_DIR)

decode: install-framework
	$(APKTOOL) d $(APK) -o $(CAL_DIR) -p $(FW_DIR)

decode-jadx:
	$(JADX) -d $(JADX_DIR) $(APK)

# 从 src/ 编译 Java 并把 smali 合入 apktool 工程
build:
	./scripts/build.sh

encode:
	@mkdir -p $(DIST)
	$(APKTOOL) b $(CAL_DIR) -o $(DIST)/Calendar_patched_unsigned.apk -p $(FW_DIR)

# zipalign + debug 签名（v2/v3）
sign: encode
	$(SIGNER) --apks $(DIST)/Calendar_patched_unsigned.apk --out $(DIST)/
	mv $(DIST)/Calendar_patched_unsigned-aligned-debugSigned.apk $(DIST)/Calendar_debug_signed.apk
	@echo "产物: $(DIST)/Calendar_debug_signed.apk"

# 完整流程：build -> sign
all: build sign

install: sign
	adb install -r $(DIST)/Calendar_debug_signed.apk

# 设备 root 下可直接读；非 root 用 run-as
pull-log:
	@mkdir -p $(DIST)
	-adb shell "run-as $(PKG) cat files/cal_dbg.log" > $(DIST)/cal_dbg.log 2>/dev/null || \
	 adb pull /data/data/$(PKG)/files/cal_dbg.log $(DIST)/cal_dbg.log
	@echo "日志: $(DIST)/cal_dbg.log"

clean:
	rm -rf $(CAL_DIR) $(JADX_DIR) $(FW_DIR) $(DIST) build
