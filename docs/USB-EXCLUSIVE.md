# USB 独占输出（实验性）

## 路径与当前范围

播放设备面板增加“USB 独占（实验性）”。枚举 USB AudioStreaming 接口，点击设备后由
Android UsbManager 请求用户授权，独占声明流接口、选择备用设置和采样率，再经 JNI
对已授权设备文件描述符提交 Linux usbfs 等时 URB。USB 音频数据直接进入 DAC，
不经 AudioFlinger 混音输出；不需要 root。

**这不是通用 USB DAC 驱动，也尚未做真实 DAC 验收。** 当前接受的描述符配置：

- 全速（Full Speed）或高速（High Speed）USB、首个配置中的 UAC1 AudioStreaming 接口；
- Type I PCM、双声道、每采样 2、3 或 4 字节，16 至 32 个有效位；
- 自适应或同步等时 OUT 端点；全速 1ms，高速 125µs／250µs／500µs／1ms，单次事务；
- 描述符支持当前解码采样率，且最大包长容纳该采样率；
- 支持采样频率 SET_CUR，或只有一个已知固定采样率。

UAC2、异步反馈端点、高速多事务端点、DSD 和多声道尚未支持。
不兼容的设备仍可显示在面板中，点击后显示原因，不能假装成功接管。

## PCM 与时钟限制

复用 `MeloXCastAudioProcessor` 的解码 PCM tap，并在向系统 AudioTrack 送出前置零。
系统 AudioTrack 仍作为本地播放器的静音时钟存在。原生驱动维持四个 URB，每个约 8ms，
按端点间隔跨包保留分数采样计数（例如全速 44.1kHz 的 44/45 帧分配），以 DAC 的 USB
传输节拍送出数据。环形缓存有界；溢出或传输错误会退出并恢复本机输出，缺数据时补零。

这条路径目前取的是 **PCM16**；输出到 24/32 位容器时仅在低位补零，高位深音源仍可能在 Media3 链中转为 PCM16。
因此不能把它宣传为原始音源的高位深 bit-perfect 输出。满音量保留 tap 字节，降低
音量是软件 PCM 衰减；播放器内部 DSP、渐变与解码转换也可能影响源数据。

静音 AudioTrack 与 DAC 并非同一时钟。长时间播放的缓存漂移、欠载及实际连续出声需要
硬件测量。后续需改为以 USB 为时钟的完整 AudioSink，接入异步反馈和 UAC2 时钟实体，
才能拓展到常见现代 DAC 与高位深直通。

## 生命周期

- USB 授权采用异步等待，不阻塞主线程，并用 UsbManager 再次核实授权结果。
- `.dev` / `.dew` 使用各自包名限定的授权 action。
- 面板关闭保持输出；主动关闭、拔线、原生错误或歌曲格式变化时退出。
- 暂停时保留尚未提交的环形缓冲，已提交的 URB 可能继续播放最多约 32ms；seek 时丢弃尚未提交的旧缓冲。
- 结束时先摘除 PCM 监听并恢复本地数据，再停止并回收原生 URB，选择零带宽备用接口、
  释放接口并尝试重新连接内核驱动；已断开的物理设备仍需重新插入才可用。
- 与弥乐 LAN 投放、一起听、DLNA、小米 MiPlay 互斥；播放服务销毁时释放会话。

## 文件

- `android/app/src/main/kotlin/com/lladlam/melox/usb/UsbAudioDescriptors.kt`：有界描述符解析及配置筛选。
- `android/app/src/main/kotlin/com/lladlam/melox/usb/UsbAudioDiagnostics.kt`：USB 速度回退和最近一次音频描述符诊断，不采集序列号字符串。
- `android/app/src/main/kotlin/com/lladlam/melox/usb/MeloXUsbAudio.kt`：授权、会话、PCM tap、异常回退与音量。
- `android/app/src/main/kotlin/com/lladlam/melox/usb/UsbAudioNative.kt`：JNI 边界。
- `android/app/src/main/cpp/usb_audio.cpp`：usbfs ISO URB、包长调度、缓存与清理。
- `android/app/src/main/kotlin/com/lladlam/melox/ui/player/UsbAudioOutputRows.kt`：设备面板入口。
- `android/app/src/test/kotlin/com/lladlam/melox/usb/UsbAudioDescriptorsTest.kt`：格式筛选、损坏描述符及 PCM 衰减测试。
- `android/app/src/test/cpp/usb_audio_test.cpp`：实际原生包长调度、暂停与 seek 缓冲测试。

## 验证

单元测试和编译不能替代硬件验收。等待用户用受支持的 DAC 验证授权、连续出声、44.1/48kHz、暂停/seek、
关闭面板继续播放、拔线/重新接管、其它输出切换、不同 ABI 与长时间时钟漂移。
设备无法接管时，可在尝试后导出应用日志；日志包含音频接口描述符、速度查询结果、解码格式和失败原因，不含 USB 序列号字符串。

平台参考：Android `UsbRequest` 只支持 bulk / interrupt（不是等时音频传输）：
<https://developer.android.com/reference/android/hardware/usb/UsbRequest>。
