# RTSP 声画同步改动说明（2026-06-14）

## 背景

在业务场景中，`RtspPlayer` 解码后视频直出到 `Surface`，音频送到外部混音器。实际表现为音频明显落后于视频，且仅在业务侧调 `presentationTimeUs` 效果有限。

为了保证：

- `RtspSurfaceView` 现有行为不受影响；
- `RtspPlayer` 支持外部 `AudioClockProvider`，未提供外部时钟时可自动使用内部 AudioTrack 时钟；
- RTP 时间戳换算不再固定使用 90k（对音频不准确）；

本次在库层做了同步能力扩展、启动阶段门控和时间戳修正。

---

## 本次改动概览

### 1) 新增可选同步接口

新增文件：

- `library-client-rtsp/src/main/java/com/alexvas/rtsp/codec/AudioVideoSync.kt`

包含：

- `AudioClockProvider`
  - `getCurrentPositionUs()`
  - `getPendingDurationUs()`
- `VideoSyncMode`
  - `LEGACY`
  - `AUDIO_MASTER`（视频跟音频）
- `AudioStartupSyncMode`
  - `LEGACY`
  - `DROP_UNTIL_FIRST_VIDEO_FRAME_RENDERED`（默认）

说明：

- `RtspPlayer` / `RtspSurfaceView` 默认启用首帧前音频门控（可通过 `audioStartupSyncMode` 调整）。
- 设置 `audioClockProvider` 后自动切换到 `AUDIO_MASTER`；清空 provider 时自动回到 `LEGACY`。
- 若未设置外部 `AudioClockProvider`，且启用了内部音频播放，库会自动使用内部 AudioTrack 时钟进入 `AUDIO_MASTER`。

### 2) 视频线程增加音频主时钟同步分支

修改文件：

- `library-client-rtsp/src/main/java/com/alexvas/rtsp/codec/VideoDecodeThread.kt`
- `library-client-rtsp/src/main/java/com/alexvas/rtsp/codec/VideoDecoderSurfaceThread.kt`

实现要点：

- 新增 `setVideoSyncMode(...)` / `setAudioClockProvider(...)`。
- 在 `VideoDecoderSurfaceThread.releaseOutputBuffer(...)` 中：
  - 若 `AUDIO_MASTER + provider != null`，按音频播放进度计算视频误差；
  - 视频过慢时丢帧追赶；
  - 视频过快时延后渲染（上限等待）。
- 否则走原有逻辑（`LEGACY` / 原稳帧逻辑）。

### 3) `RtspProcessor` / `RtspPlayer` / `RtspSurfaceView` 配置透传

修改文件：

- `library-client-rtsp/src/main/java/com/alexvas/rtsp/widget/RtspProcessor.kt`
- `library-client-rtsp/src/main/java/com/alexvas/rtsp/widget/RtspPlayer.kt`
- `library-client-rtsp/src/main/java/com/alexvas/rtsp/widget/RtspSurfaceView.kt`

新增字段：

- `audioClockProvider`（自动驱动 `AUDIO_MASTER/LEGACY` 切换）
- `audioStartupSyncMode`
- `audioStartupDropTimeoutMs`
- `internalAudioAutoCompensationEnabled`（内部 AudioTrack 链路是否启用 AUTO 补偿，默认 `true`）

补充：

- `RtspProcessor` 支持内部 AudioTrack 时钟；当外部 `audioClockProvider` 为空且内部播放开启时，自动作为音频主时钟。
- `RtspPlayer` / `RtspSurfaceView` 行为一致：外部时钟优先，外部为空时自动回落内部时钟链路。
- 内部 AudioTrack 链路使用自适应补偿（AUTO）策略，无需业务侧手工调节固定补偿值。
- 可通过 `internalAudioAutoCompensationEnabled=false` 关闭 AUTO 补偿（仅内部时钟链路）。

并在视频线程启动时透传。

### 4) RTP 时间戳按 track clock rate 修正（关键）

修改文件：

- `library-client-rtsp/src/main/java/com/alexvas/rtsp/parser/RtpHeaderParser.java`
- `library-client-rtsp/src/main/java/com/alexvas/rtsp/RtspClient.java`

核心变化：

- 原先 `getTimestampMsec()` 固定按 90k 计算（`timeStamp * 11.111111`）。
- 现在新增 `getTimestampMsec(int clockRateHz)`，按：
  - `timestampUs = rtpTs * 1_000_000 / clockRateHz`
- 在 `RtspClient` 中为每个 `Track` 增加 `clockRateHz`，从 SDP 的 `rtpmap` 解析填充：
  - 视频一般 `90000`
  - 音频一般为采样率（如 `8000 / 16000 / 48000`）
- 分发回调时按对应轨道时钟换算时间戳。

> 注意：方法名历史原因仍叫 `getTimestampMsec`，但返回值单位为微秒（us）。

---

## 行为结论（当前版本）

- `RtspSurfaceView` / `RtspPlayer`：默认启用 `DROP_UNTIL_FIRST_VIDEO_FRAME_RENDERED`，减少启动音频先入队导致的初始不同步。
- `RtspPlayer`：无需手动设置 `videoSyncMode`，设置外部 `audioClockProvider` 即自动进入 `AUDIO_MASTER`。
- `RtspPlayer`：未设置外部时钟且内部音频播放开启时，会自动使用内部 AudioTrack 时钟进入 `AUDIO_MASTER`。
- 内部 AudioTrack 路径默认使用 AUTO 自适应补偿，并结合 `pendingDurationUs` 动态调整。
- `internalAudioAutoCompensationEnabled=false` 时，内部时钟链路不再应用 AUTO 补偿。

---

## 业务侧接入示例（`RtspPlayer`）

```kotlin
import com.alexvas.rtsp.codec.AudioClockProvider

rtspPlayer.audioClockProvider = object : AudioClockProvider {
    override fun getCurrentPositionUs(): Long {
        // 这里返回业务混音器/音轨的当前播放进度
        return trackHandle?.currentPositionUs ?: 0L
    }

    override fun getPendingDurationUs(): Long {
        // 可选：返回排队时长，用于策略扩展
        return trackHandle?.pendingDurationUs ?: 0L
    }
}
```

如果不需要该能力，清空 provider 即可回到 `LEGACY`：

```kotlin
rtspPlayer.audioClockProvider = null
```

`RtspPlayer` 支持通过内部 AudioTrack 时钟自动启用 `AUDIO_MASTER`（前提：未设置外部 `audioClockProvider` 且 `audioPlaybackEnabled=true`）。

---

## 内部 AudioTrack 自适应补偿（`RtspSurfaceView`/内部链路）

当使用内部 AudioTrack 时钟作为音频主时钟时，库内部自动执行自适应补偿：

- 基于 `AudioClockProvider.getPendingDurationUs()` 估计当前音频管线延迟；
- 使用平滑和步进限制，避免补偿值频繁抖动；
- 业务侧不再需要设置 `internalAudioMasterCompensationUs`。

可选地，业务侧可关闭 AUTO 补偿：

```kotlin
rtspPlayer.internalAudioAutoCompensationEnabled = false
// 或
rtspSurfaceView.internalAudioAutoCompensationEnabled = false
```

---

## 明日验证清单（建议）

1. **回归**：`RtspSurfaceView` 播放（确认与改动前一致）。
2. **新路径**：`RtspPlayer + AUDIO_MASTER + 外部混音`。
3. **对比项**：
   - 开播 10 秒内音画偏差；
   - 1~3 分钟后是否漂移；
   - 网络抖动后恢复速度。
4. **不同音频参数**（若有条件）：
   - 8k / 16k / 48k；
   - AAC / G711。
5. **日志建议（业务侧）**：
   - 打印 `getCurrentPositionUs`、`getPendingDurationUs`；
   - 打印首帧渲染时间和首个音频送混时间；
   - 当前库已移除调试期 `AUDIO_MASTER` 详细 `Log.i`。

---

## 已执行的编译检查

已通过：

- `:library-client-rtsp:compileDebugKotlin`
- `:library-client-rtsp:compileDebugJavaWithJavac`

