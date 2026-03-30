Live Streaming Camera

一個使用 StreamPack boilerplate (https://github.com/ThibaultBee/StreamPack-boilerplate) 作底層製作的Android直播程式。


## 功能

- ✅ RTMP 推流直播,Twitch 直接推流。
- ✅ SRT 推流直播。
- ✅ Twitch 聊天室，直播人數，直播時間。
- ✅ 藍牙耳機連線。


## 安裝方法
我是使用 [GitHub releases](https://github.com/kongjjj/Live-Streaming-Camera/releases) 發布來發布 .apk 檔案。

可以在手機上開啟 GitHub 發行頁面,下載 .apk 檔案並安裝。
1. Click on "Use this template" to create a new repository from this template.
2. Clone your new repository.
3. Open the project with Android Studio.
4. Replace default `rtmp://my.server.url:1935/app/streamKey` by your RTMP or SRT server URL in
   `MainViewModel.kt`.
5. Run the application on a device or an emulator.
