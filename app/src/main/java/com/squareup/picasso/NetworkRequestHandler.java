/*
 * Copyright (C) 2013 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.picasso;

import android.net.NetworkInfo;
import java.io.IOException;
import okhttp3.CacheControl;
import okhttp3.Response;
import okhttp3.ResponseBody;

import static com.squareup.picasso.Picasso.LoadedFrom.DISK;
import static com.squareup.picasso.Picasso.LoadedFrom.NETWORK;

class NetworkRequestHandler extends RequestHandler {
  private static final String SCHEME_HTTP = "http";
  private static final String SCHEME_HTTPS = "https";

  private final Downloader downloader;
  private final Stats stats;

  public NetworkRequestHandler(Downloader downloader, Stats stats) {
    this.downloader = downloader;
    this.stats = stats;
  }

  /**
   * 判断可以处理的scheme类型
     */
  @Override public boolean canHandleRequest(Request data) {
    String scheme = data.uri.getScheme();
    return (SCHEME_HTTP.equals(scheme) || SCHEME_HTTPS.equals(scheme));
  }

  @Override public Result load(Request request, int networkPolicy) throws IOException {
    okhttp3.Request downloaderRequest = createRequest(request, networkPolicy);
    //这里的downloader默认为OkHttp3Downloader
    //实际上也没啥，就是把创建的Request转为RealCall进行执行而已
    //注意这里是同步获取响应而已，必须在子线程中进行
    Response response = downloader.load(downloaderRequest);
    ResponseBody body = response.body();

    if (!response.isSuccessful()) {
      body.close();
      throw new ResponseException(response.code(), request.networkPolicy);
    }

    // Cache response is only null when the response comes fully from the network. Both completely
    // cached and conditionally cached responses will have a non-null cache response.
    // 在OkHttp3的Response中一般来说会有cacheResponse和networkResponse参数
    // 。。。
    Picasso.LoadedFrom loadedFrom = response.cacheResponse() == null ? NETWORK : DISK;

    // Sometimes response content length is zero when requests are being replayed. Haven't found
    // root cause to this but retrying the request seems safe to do so.
    if (loadedFrom == DISK && body.contentLength() == 0) {
      body.close();
      throw new ContentLengthException("Received response with 0 content-length header.");
    }
    if (loadedFrom == NETWORK && body.contentLength() > 0) {
      stats.dispatchDownloadFinished(body.contentLength());
    }
    return new Result(body.source(), loadedFrom);
  }

  @Override int getRetryCount() {
    return 2;
  }

  @Override boolean shouldRetry(boolean airplaneMode, NetworkInfo info) {
    return info == null || info.isConnected();
  }

  @Override boolean supportsReplay() {
    return true;
  }

  private static okhttp3.Request createRequest(Request request, int networkPolicy) {
    //可以看到Picasso默认是使用Http缓存来处理图片的硬盘缓存的
    CacheControl cacheControl = null;
    if (networkPolicy != 0) {//如果网络策略不为no_cache
      if (NetworkPolicy.isOfflineOnly(networkPolicy)) {//当前策略为不从网络中获取数据
        //这个在OkHttp3中的实现是only-if-cache头部标记，也就是只从本地网络缓存中获取数据
        //如果缓存没有也不会发起网络请求
        cacheControl = CacheControl.FORCE_CACHE;
      } else {
        CacheControl.Builder builder = new CacheControl.Builder();
        //注意无论是no_cache还是no_store都不会使用硬盘缓存
        if (!NetworkPolicy.shouldReadFromDiskCache(networkPolicy)) {
          builder.noCache();
        }
        //如果在no_store情况下，在no_cache状态下服务端返回的响应也不会进行硬盘存储
        if (!NetworkPolicy.shouldWriteToDiskCache(networkPolicy)) {
          builder.noStore();
        }
        cacheControl = builder.build();
      }
    }

    okhttp3.Request.Builder builder = new okhttp3.Request.Builder().url(request.uri.toString());
    //标记缓存策略
    if (cacheControl != null) {
      builder.cacheControl(cacheControl);
    }
    return builder.build();
  }

  static class ContentLengthException extends IOException {
    ContentLengthException(String message) {
      super(message);
    }
  }

  static final class ResponseException extends IOException {
    final int code;
    final int networkPolicy;

    ResponseException(int code, int networkPolicy) {
      super("HTTP " + code);
      this.code = code;
      this.networkPolicy = networkPolicy;
    }
  }
}
