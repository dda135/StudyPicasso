/*
 * Copyright (C) 2014 Square, Inc.
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

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.NetworkInfo;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import java.io.IOException;
import okio.Source;

import static com.squareup.picasso.Utils.checkNotNull;

/**
 * {@code RequestHandler} allows you to extend Picasso to load images in ways that are not
 * supported by default in the library.
 * <p>
 * <h2>Usage</h2>
 * {@code RequestHandler} must be subclassed to be used. You will have to override two methods
 * ({@link #canHandleRequest(Request)} and {@link #load(Request, int)}) with your custom logic to
 * load images.
 * <p>
 * You should then register your {@link RequestHandler} using
 * {@link Picasso.Builder#addRequestHandler(RequestHandler)}
 * <p>
 * <b>Note:</b> This is a beta feature. The API is subject to change in a backwards incompatible
 * way at any time.
 *
 * @see Picasso.Builder#addRequestHandler(RequestHandler)
 */
public abstract class RequestHandler {
  /**
   * {@link Result} represents the result of a {@link #load(Request, int)} call in a
   * {@link RequestHandler}.
   *
   * @see RequestHandler
   * @see #load(Request, int)
   */
  public static final class Result {
    private final Picasso.LoadedFrom loadedFrom;
    private final Bitmap bitmap;
    private final Source source;
    private final int exifOrientation;

    public Result(@NonNull Bitmap bitmap, @NonNull Picasso.LoadedFrom loadedFrom) {
      this(checkNotNull(bitmap, "bitmap == null"), null, loadedFrom, 0);
    }

    public Result(@NonNull Source source, @NonNull Picasso.LoadedFrom loadedFrom) {
      this(null, checkNotNull(source, "source == null"), loadedFrom, 0);
    }

    Result(
        @Nullable Bitmap bitmap,
        @Nullable Source source,
        @NonNull Picasso.LoadedFrom loadedFrom,
        int exifOrientation) {
      if ((bitmap != null) == (source != null)) {
        throw new AssertionError();
      }
      this.bitmap = bitmap;
      this.source = source;
      this.loadedFrom = checkNotNull(loadedFrom, "loadedFrom == null");
      this.exifOrientation = exifOrientation;
    }

    /** The loaded {@link Bitmap}. Mutually exclusive with {@link #getSource()}. */
    @Nullable public Bitmap getBitmap() {
      return bitmap;
    }

    /** A stream of image data. Mutually exclusive with {@link #getBitmap()}. */
    @Nullable public Source getSource() {
      return source;
    }

    /**
     * Returns the resulting {@link Picasso.LoadedFrom} generated from a
     * {@link #load(Request, int)} call.
     */
    @NonNull public Picasso.LoadedFrom getLoadedFrom() {
      return loadedFrom;
    }

    /**
     * Returns the resulting EXIF orientation generated from a {@link #load(Request, int)} call.
     * This is only accessible to built-in RequestHandlers.
     */
    int getExifOrientation() {
      return exifOrientation;
    }
  }

  /**
   * Whether or not this {@link RequestHandler} can handle a request with the given {@link Request}.
   */
  public abstract boolean canHandleRequest(Request data);

  /**
   * Loads an image for the given {@link Request}.
   *
   * @param request the data from which the image should be resolved.
   * @param networkPolicy the {@link NetworkPolicy} for this request.
   */
  @Nullable public abstract Result load(Request request, int networkPolicy) throws IOException;

  int getRetryCount() {
    return 0;
  }

  boolean shouldRetry(boolean airplaneMode, NetworkInfo info) {
    return false;
  }

  boolean supportsReplay() {
    return false;
  }

  /**
   * Lazily create {@link BitmapFactory.Options} based in given
   * {@link Request}, only instantiating them if needed.
   * 在进行Bitmap操作之前要先获得压缩参数
   */
  static BitmapFactory.Options createBitmapOptions(Request data) {
    final boolean justBounds = data.hasSize();
    final boolean hasConfig = data.config != null;
    BitmapFactory.Options options = null;
    //对于一般来说是必须要指定BitmapConfig的
    if (justBounds || hasConfig || data.purgeable) {
      options = new BitmapFactory.Options();
      //如果指定了载体的大小，那么可能要进行压缩操作，所以一开始不应该将Bitmap加载进内存当中
      options.inJustDecodeBounds = justBounds;
      options.inInputShareable = data.purgeable;
      options.inPurgeable = data.purgeable;
      if (hasConfig) {
        options.inPreferredConfig = data.config;
      }
    }
    return options;
  }

  static boolean requiresInSampleSize(BitmapFactory.Options options) {
    return options != null && options.inJustDecodeBounds;
  }

  static void calculateInSampleSize(int reqWidth, int reqHeight, BitmapFactory.Options options,
      Request request) {
    calculateInSampleSize(reqWidth, reqHeight, options.outWidth, options.outHeight, options,
        request);
  }

  /**
   * 计算压缩的比例
   * @param reqWidth 载体的宽度
   * @param reqHeight 载体的高度
   * @param width 当前Bitmap的宽度
   * @param height 当前Bitmap的高度
     */
  static void calculateInSampleSize(int reqWidth, int reqHeight, int width, int height,
      BitmapFactory.Options options, Request request) {
    int sampleSize = 1;
    //如果当前Bitmap的高或宽大于载体的高或宽，要增加采样率
    if (height > reqHeight || width > reqWidth) {
      final int heightRatio;
      final int widthRatio;
      if (reqHeight == 0) {
        sampleSize = (int) Math.floor((float) width / (float) reqWidth);
      } else if (reqWidth == 0) {
        sampleSize = (int) Math.floor((float) height / (float) reqHeight);
      } else {//一般来说看这里就行
        heightRatio = (int) Math.floor((float) height / (float) reqHeight);
        widthRatio = (int) Math.floor((float) width / (float) reqWidth);
        //如果centerInside模式下，要保证压缩后的内容都在载体当中，取缩放的最大值
        //这样会有长或宽刚好相等，但是宽或搞肯定会小于或等于载体宽或高
        //否则，只缩放到宽或高刚好同载体相等，这样宽或高有可能大于载体的宽或高
        //不过要注意inSampleSize必须为2的倍数，否则自动向下取最近的值，这可能会导致采样完的依旧偏大的情况出现
        sampleSize = request.centerInside
            ? Math.max(heightRatio, widthRatio)
            : Math.min(heightRatio, widthRatio);
      }
    }
    //设置采样率，并且准备加载Bitmap到内存
    options.inSampleSize = sampleSize;
    options.inJustDecodeBounds = false;
  }
}
