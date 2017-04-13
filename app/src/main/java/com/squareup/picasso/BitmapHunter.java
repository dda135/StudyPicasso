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

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.NetworkInfo;
import android.os.Build;
import android.view.Gravity;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import okio.BufferedSource;
import okio.Okio;
import okio.Source;

import static android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL;
import static android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL;
import static android.media.ExifInterface.ORIENTATION_ROTATE_180;
import static android.media.ExifInterface.ORIENTATION_ROTATE_270;
import static android.media.ExifInterface.ORIENTATION_ROTATE_90;
import static android.media.ExifInterface.ORIENTATION_TRANSPOSE;
import static android.media.ExifInterface.ORIENTATION_TRANSVERSE;
import static com.squareup.picasso.MemoryPolicy.shouldReadFromMemoryCache;
import static com.squareup.picasso.Picasso.LoadedFrom.MEMORY;
import static com.squareup.picasso.Picasso.Priority;
import static com.squareup.picasso.Picasso.Priority.LOW;
import static com.squareup.picasso.Utils.OWNER_HUNTER;
import static com.squareup.picasso.Utils.VERB_DECODED;
import static com.squareup.picasso.Utils.VERB_EXECUTING;
import static com.squareup.picasso.Utils.VERB_JOINED;
import static com.squareup.picasso.Utils.VERB_REMOVED;
import static com.squareup.picasso.Utils.VERB_TRANSFORMED;
import static com.squareup.picasso.Utils.getLogIdsForHunter;
import static com.squareup.picasso.Utils.log;

class BitmapHunter implements Runnable {
  /**
   * Global lock for bitmap decoding to ensure that we are only are decoding one at a time. Since
   * this will only ever happen in background threads we help avoid excessive memory thrashing as
   * well as potential OOMs. Shamelessly stolen from Volley.
   */
  private static final Object DECODE_LOCK = new Object();

  private static final ThreadLocal<StringBuilder> NAME_BUILDER = new ThreadLocal<StringBuilder>() {
    @Override protected StringBuilder initialValue() {
      return new StringBuilder(Utils.THREAD_PREFIX);
    }
  };

  private static final AtomicInteger SEQUENCE_GENERATOR = new AtomicInteger();

  private static final RequestHandler ERRORING_HANDLER = new RequestHandler() {
    @Override public boolean canHandleRequest(Request data) {
      return true;
    }

    @Override public Result load(Request request, int networkPolicy) throws IOException {
      throw new IllegalStateException("Unrecognized type of request: " + request);
    }
  };

  final int sequence;
  final Picasso picasso;
  final Dispatcher dispatcher;
  final Cache cache;
  final Stats stats;
  final String key;
  final Request data;
  final int memoryPolicy;
  int networkPolicy;
  final RequestHandler requestHandler;

  Action action;
  List<Action> actions;
  Bitmap result;
  Future<?> future;
  Picasso.LoadedFrom loadedFrom;
  Exception exception;
  int exifOrientation; // Determined during decoding of original resource.
  int retryCount;
  Priority priority;

  BitmapHunter(Picasso picasso, Dispatcher dispatcher, Cache cache, Stats stats, Action action,
      RequestHandler requestHandler) {
    this.sequence = SEQUENCE_GENERATOR.incrementAndGet();
    this.picasso = picasso;
    this.dispatcher = dispatcher;
    this.cache = cache;
    this.stats = stats;
    this.action = action;
    this.key = action.getKey();
    this.data = action.getRequest();
    this.priority = action.getPriority();
    this.memoryPolicy = action.getMemoryPolicy();
    this.networkPolicy = action.getNetworkPolicy();
    this.requestHandler = requestHandler;
    this.retryCount = requestHandler.getRetryCount();
  }

  /**
   * Decode a byte stream into a Bitmap. This method will take into account additional information
   * about the supplied request in order to do the decoding efficiently (such as through leveraging
   * {@code inSampleSize}).
   */
  static Bitmap decodeStream(Source source, Request request) throws IOException {
    BufferedSource bufferedSource = Okio.buffer(source);

    boolean isWebPFile = Utils.isWebPFile(bufferedSource);
    boolean isPurgeable = request.purgeable && Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP;
    //根据Request的参数获取解析Bitmap的Options
    BitmapFactory.Options options = RequestHandler.createBitmapOptions(request);
    //如果载体有着明确的大小则是有必要进行Bitmap大小的重新计算
    boolean calculateSize = RequestHandler.requiresInSampleSize(options);

    // We decode from a byte array because, a) when decoding a WebP network stream, BitmapFactory
    // throws a JNI Exception, so we workaround by decoding a byte array, or b) user requested
    // purgeable, which only affects bitmaps decoded from byte arrays.
    if (isWebPFile || isPurgeable) {
      byte[] bytes = bufferedSource.readByteArray();
      if (calculateSize) {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        RequestHandler.calculateInSampleSize(request.targetWidth, request.targetHeight, options,
            request);
      }
      return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    } else {
      //一般来说jpg看这里就好
      InputStream stream = bufferedSource.inputStream();
      if (calculateSize) {//如果需要重新计算大小
        // TODO use an InputStream that buffers with Okio...
        MarkableInputStream markStream = new MarkableInputStream(stream);
        stream = markStream;
        markStream.allowMarksToExpire(false);
        long mark = markStream.savePosition(1024);
        //注意此时的inJustDecodeBounds为true，可以获得Bitmap的大小，但是不会实际加载进内存当中
        BitmapFactory.decodeStream(stream, null, options);
        //实际的计算算法
        RequestHandler.calculateInSampleSize(request.targetWidth, request.targetHeight, options,
            request);
        //重置流，这样才可以再次通过BitmapFactory解析Bitmap
        markStream.reset(mark);
        markStream.allowMarksToExpire(true);
      }
      //此时inJustDecodeBounds为false，则为实际解析Bitmap
      Bitmap bitmap = BitmapFactory.decodeStream(stream, null, options);
      if (bitmap == null) {
        // Treat null as an IO exception, we will eventually retry.
        throw new IOException("Failed to decode stream.");
      }
      return bitmap;
    }
  }

  @Override public void run() {
    try {
      updateThreadName(data);

      if (picasso.loggingEnabled) {
        log(OWNER_HUNTER, VERB_EXECUTING, getLogIdsForHunter(this));
      }

      result = hunt();
      //分发结果
      if (result == null) {
        dispatcher.dispatchFailed(this);
      } else {
        dispatcher.dispatchComplete(this);
      }
    } catch (NetworkRequestHandler.ResponseException e) {
      if (!NetworkPolicy.isOfflineOnly(e.networkPolicy) || e.code != 504) {
        exception = e;
      }
      dispatcher.dispatchFailed(this);
    } catch (IOException e) {
      exception = e;
      dispatcher.dispatchRetry(this);
    } catch (OutOfMemoryError e) {
      StringWriter writer = new StringWriter();
      stats.createSnapshot().dump(new PrintWriter(writer));
      exception = new RuntimeException(writer.toString(), e);
      dispatcher.dispatchFailed(this);
    } catch (Exception e) {
      exception = e;
      dispatcher.dispatchFailed(this);
    } finally {
      Thread.currentThread().setName(Utils.THREAD_IDLE_NAME);
    }
  }

  /**
   * 实际的执行过程来获取Bitmap
   * @throws IOException
     */
  Bitmap hunt() throws IOException {
    Bitmap bitmap = null;

    if (shouldReadFromMemoryCache(memoryPolicy)) {//是否允许使用内存缓存
      bitmap = cache.get(key);
      if (bitmap != null) {//击中内存缓存
        stats.dispatchCacheHit();//进行数据统计
        loadedFrom = MEMORY;//标记来源为内存
        if (picasso.loggingEnabled) {
          log(OWNER_HUNTER, VERB_DECODED, data.logId(), "from cache");
        }
        return bitmap;
      }
    }
    //这里有点坑，总之就是如果不尝试重试，那么默认是不会进行从网络上获取图片这一过程
    //对于默认的RequestHandler来说都是不会进行重新尝试
    //这个时候采用的是不从网络中加载图片的策略
    //在默认实现的RequestHandler中就用于加载资源、相册等数据源的图片
    //默认的NetworkRequestHandler中retryCount为2
    networkPolicy = retryCount == 0 ? NetworkPolicy.OFFLINE.index : networkPolicy;
    //可以看到，具体加载图片的流程都是在RequestHandler中进行，可以是从网络加载图片也可以是读取资源图片等等
    RequestHandler.Result result = requestHandler.load(data, networkPolicy);
    if (result != null) {
      loadedFrom = result.getLoadedFrom();
      exifOrientation = result.getExifOrientation();
      bitmap = result.getBitmap();

      // If there was no Bitmap then we need to decode it from the stream.
      // 这个稍微注意一下，使用NetworkRequestHandler中只会关联最后的流，而不会直接得到Bitmap
      if (bitmap == null) {
        Source source = result.getSource();
        try {
          //内部进行了采样率的压缩
          bitmap = decodeStream(source, data);
          //注意此时出来的可能还会偏大
        } finally {
          try {
            //noinspection ConstantConditions If bitmap is null then source is guranteed non-null.
            source.close();
          } catch (IOException ignored) {
          }
        }
      }
    }

    if (bitmap != null) {
      if (picasso.loggingEnabled) {
        log(OWNER_HUNTER, VERB_DECODED, data.logId());
      }
      stats.dispatchBitmapDecoded(bitmap);
      //此时Bitmap已经压缩完成，这里是判断是否进行额外的转换操作
      if (data.needsTransformation() || exifOrientation != 0) {
        synchronized (DECODE_LOCK) {
          //一般来说如果指定了载体的大小，都是需要进行一次矩阵操作的
          if (data.needsMatrixTransform() || exifOrientation != 0) {
            bitmap = transformResult(data, bitmap, exifOrientation);
            if (picasso.loggingEnabled) {
              log(OWNER_HUNTER, VERB_TRANSFORMED, data.logId());
            }
          }
          if (data.hasCustomTransformations()) {
            bitmap = applyCustomTransformations(data.transformations, bitmap);
            if (picasso.loggingEnabled) {
              log(OWNER_HUNTER, VERB_TRANSFORMED, data.logId(), "from custom transformations");
            }
          }
        }
        if (bitmap != null) {
          stats.dispatchBitmapTransformed(bitmap);
        }
      }
    }

    return bitmap;
  }

  /**.
   * 如果在当前Action提交之后发现已经有对应的Hunter处理同一个URL的时候
   * 会进行关联
   * @param action 当前发出的Action
     */
  void attach(Action action) {
    boolean loggingEnabled = picasso.loggingEnabled;
    Request request = action.request;

    if (this.action == null) {
      this.action = action;
      if (loggingEnabled) {
        if (actions == null || actions.isEmpty()) {
          log(OWNER_HUNTER, VERB_JOINED, request.logId(), "to empty hunter");
        } else {
          log(OWNER_HUNTER, VERB_JOINED, request.logId(), getLogIdsForHunter(this, "to "));
        }
      }
      return;
    }

    if (actions == null) {
      actions = new ArrayList<>(3);
    }

    actions.add(action);

    if (loggingEnabled) {
      log(OWNER_HUNTER, VERB_JOINED, request.logId(), getLogIdsForHunter(this, "to "));
    }

    Priority actionPriority = action.getPriority();
    if (actionPriority.ordinal() > priority.ordinal()) {
      priority = actionPriority;
    }
  }

  /**
   * detach会取消当前Action和Hunter的关联
   * @param action
     */
  void detach(Action action) {
    boolean detached = false;
    if (this.action == action) {
      this.action = null;
      detached = true;
    } else if (actions != null) {
      detached = actions.remove(action);
    }

    // The action being detached had the highest priority. Update this
    // hunter's priority with the remaining actions.
    if (detached && action.getPriority() == priority) {
      priority = computeNewPriority();
    }

    if (picasso.loggingEnabled) {
      log(OWNER_HUNTER, VERB_REMOVED, action.request.logId(), getLogIdsForHunter(this, "from "));
    }
  }

  private Priority computeNewPriority() {
    Priority newPriority = LOW;

    boolean hasMultiple = actions != null && !actions.isEmpty();
    boolean hasAny = action != null || hasMultiple;

    // Hunter has no requests, low priority.
    if (!hasAny) {
      return newPriority;
    }

    if (action != null) {
      newPriority = action.getPriority();
    }

    if (hasMultiple) {
      //noinspection ForLoopReplaceableByForEach
      for (int i = 0, n = actions.size(); i < n; i++) {
        Priority actionPriority = actions.get(i).getPriority();
        if (actionPriority.ordinal() > newPriority.ordinal()) {
          newPriority = actionPriority;
        }
      }
    }

    return newPriority;
  }

  boolean cancel() {
    return action == null
        && (actions == null || actions.isEmpty())
        && future != null
        && future.cancel(false);
  }

  boolean isCancelled() {
    return future != null && future.isCancelled();
  }

  boolean shouldRetry(boolean airplaneMode, NetworkInfo info) {
    boolean hasRetries = retryCount > 0;
    if (!hasRetries) {
      return false;
    }
    retryCount--;
    return requestHandler.shouldRetry(airplaneMode, info);
  }

  boolean supportsReplay() {
    return requestHandler.supportsReplay();
  }

  Bitmap getResult() {
    return result;
  }

  String getKey() {
    return key;
  }

  int getMemoryPolicy() {
    return memoryPolicy;
  }

  Request getData() {
    return data;
  }

  Action getAction() {
    return action;
  }

  Picasso getPicasso() {
    return picasso;
  }

  List<Action> getActions() {
    return actions;
  }

  Exception getException() {
    return exception;
  }

  Picasso.LoadedFrom getLoadedFrom() {
    return loadedFrom;
  }

  Priority getPriority() {
    return priority;
  }

  static void updateThreadName(Request data) {
    String name = data.getName();

    StringBuilder builder = NAME_BUILDER.get();
    builder.ensureCapacity(Utils.THREAD_PREFIX.length() + name.length());
    builder.replace(Utils.THREAD_PREFIX.length(), builder.length(), name);

    Thread.currentThread().setName(builder.toString());
  }

  static BitmapHunter forRequest(Picasso picasso, Dispatcher dispatcher, Cache cache, Stats stats,
      Action action) {
    Request request = action.getRequest();
    List<RequestHandler> requestHandlers = picasso.getRequestHandlers();

    // Index-based loop to avoid allocating an iterator.
    //noinspection ForLoopReplaceableByForEach
    for (int i = 0, count = requestHandlers.size(); i < count; i++) {
      RequestHandler requestHandler = requestHandlers.get(i);
      if (requestHandler.canHandleRequest(request)) {
        return new BitmapHunter(picasso, dispatcher, cache, stats, action, requestHandler);
      }
    }

    return new BitmapHunter(picasso, dispatcher, cache, stats, action, ERRORING_HANDLER);
  }

  static Bitmap applyCustomTransformations(List<Transformation> transformations, Bitmap result) {
    for (int i = 0, count = transformations.size(); i < count; i++) {
      final Transformation transformation = transformations.get(i);
      Bitmap newResult;
      try {
        newResult = transformation.transform(result);
      } catch (final RuntimeException e) {
        Picasso.HANDLER.post(new Runnable() {
          @Override public void run() {
            throw new RuntimeException(
                "Transformation " + transformation.key() + " crashed with exception.", e);
          }
        });
        return null;
      }

      if (newResult == null) {
        final StringBuilder builder = new StringBuilder() //
            .append("Transformation ")
            .append(transformation.key())
            .append(" returned null after ")
            .append(i)
            .append(" previous transformation(s).\n\nTransformation list:\n");
        for (Transformation t : transformations) {
          builder.append(t.key()).append('\n');
        }
        Picasso.HANDLER.post(new Runnable() {
          @Override public void run() {
            throw new NullPointerException(builder.toString());
          }
        });
        return null;
      }

      if (newResult == result && result.isRecycled()) {
        Picasso.HANDLER.post(new Runnable() {
          @Override public void run() {
            throw new IllegalStateException("Transformation "
                + transformation.key()
                + " returned input Bitmap but recycled it.");
          }
        });
        return null;
      }

      // If the transformation returned a new bitmap ensure they recycled the original.
      if (newResult != result && !result.isRecycled()) {
        Picasso.HANDLER.post(new Runnable() {
          @Override public void run() {
            throw new IllegalStateException("Transformation "
                + transformation.key()
                + " mutated input Bitmap but failed to recycle the original.");
          }
        });
        return null;
      }

      result = newResult;
    }
    return result;
  }

  /**
   * 对压缩过的Bitmap进行操作
   * 这种场景常用于照相后的jpg图片需要旋转和指定载体宽高需要拉伸等
     * @return
     */
  static Bitmap transformResult(Request data, Bitmap result, int exifOrientation) {
    int inWidth = result.getWidth();
    int inHeight = result.getHeight();
    boolean onlyScaleDown = data.onlyScaleDown;

    int drawX = 0;
    int drawY = 0;
    int drawWidth = inWidth;
    int drawHeight = inHeight;

    Matrix matrix = new Matrix();

    if (data.needsMatrixTransform() || exifOrientation != 0) {
      int targetWidth = data.targetWidth;
      int targetHeight = data.targetHeight;

      float targetRotation = data.rotationDegrees;
      if (targetRotation != 0) {
        //处理图片的旋转
        double cosR = Math.cos(Math.toRadians(targetRotation));
        double sinR = Math.sin(Math.toRadians(targetRotation));
        if (data.hasRotationPivot) {
          matrix.setRotate(targetRotation, data.rotationPivotX, data.rotationPivotY);
          // Recalculate dimensions after rotation around pivot point
          double x1T = data.rotationPivotX * (1.0 - cosR) + (data.rotationPivotY * sinR);
          double y1T = data.rotationPivotY * (1.0 - cosR) - (data.rotationPivotX * sinR);
          double x2T = x1T + (data.targetWidth * cosR);
          double y2T = y1T + (data.targetWidth * sinR);
          double x3T = x1T + (data.targetWidth * cosR) - (data.targetHeight * sinR);
          double y3T = y1T + (data.targetWidth * sinR) + (data.targetHeight * cosR);
          double x4T = x1T - (data.targetHeight * sinR);
          double y4T = y1T + (data.targetHeight * cosR);

          double maxX = Math.max(x4T, Math.max(x3T, Math.max(x1T, x2T)));
          double minX = Math.min(x4T, Math.min(x3T, Math.min(x1T, x2T)));
          double maxY = Math.max(y4T, Math.max(y3T, Math.max(y1T, y2T)));
          double minY = Math.min(y4T, Math.min(y3T, Math.min(y1T, y2T)));
          targetWidth = (int) Math.floor(maxX - minX);
          targetHeight  = (int) Math.floor(maxY - minY);
        } else {
          matrix.setRotate(targetRotation);
          // Recalculate dimensions after rotation (around origin)
          double x1T = 0.0;
          double y1T = 0.0;
          double x2T = (data.targetWidth * cosR);
          double y2T = (data.targetWidth * sinR);
          double x3T = (data.targetWidth * cosR) - (data.targetHeight * sinR);
          double y3T = (data.targetWidth * sinR) + (data.targetHeight * cosR);
          double x4T = -(data.targetHeight * sinR);
          double y4T = (data.targetHeight * cosR);

          double maxX = Math.max(x4T, Math.max(x3T, Math.max(x1T, x2T)));
          double minX = Math.min(x4T, Math.min(x3T, Math.min(x1T, x2T)));
          double maxY = Math.max(y4T, Math.max(y3T, Math.max(y1T, y2T)));
          double minY = Math.min(y4T, Math.min(y3T, Math.min(y1T, y2T)));
          targetWidth = (int) Math.floor(maxX - minX);
          targetHeight  = (int) Math.floor(maxY - minY);
        }
      }

      // EXIf interpretation should be done before cropping in case the dimensions need to
      // be recalculated
      if (exifOrientation != 0) {
        int exifRotation = getExifRotation(exifOrientation);
        int exifTranslation = getExifTranslation(exifOrientation);
        if (exifRotation != 0) {
          matrix.preRotate(exifRotation);
          if (exifRotation == 90 || exifRotation == 270) {
             // Recalculate dimensions after exif rotation
             int tmpHeight = targetHeight;
             targetHeight = targetWidth;
             targetWidth = tmpHeight;
          }
        }
        if (exifTranslation != 1) {
          matrix.postScale(exifTranslation, 1);
        }
      }
      //一般情况下直接看这里就可以了
      if (data.centerCrop) {
        // Keep aspect ratio if one dimension is set to 0
        float widthRatio =
            targetWidth != 0 ? targetWidth / (float) inWidth : targetHeight / (float) inHeight;
        float heightRatio =
            targetHeight != 0 ? targetHeight / (float) inHeight : targetWidth / (float) inWidth;
        float scaleX, scaleY;
        if (widthRatio > heightRatio) {
          int newSize = (int) Math.ceil(inHeight * (heightRatio / widthRatio));
          if ((data.centerCropGravity & Gravity.TOP) == Gravity.TOP) {
            drawY = 0;
          } else if ((data.centerCropGravity & Gravity.BOTTOM) == Gravity.BOTTOM) {
            drawY = inHeight - newSize;
          } else {
            drawY = (inHeight - newSize) / 2;
          }
          drawHeight = newSize;
          scaleX = widthRatio;
          scaleY = targetHeight / (float) drawHeight;
        } else if (widthRatio < heightRatio) {
          int newSize = (int) Math.ceil(inWidth * (widthRatio / heightRatio));
          //最终都会拉伸成Target的宽高，注意这里选择的其实像素位置
          //高度相关是直接进行压缩
          //LEFT：0，这意味着最右边的像素可能会丢失
          //RIGHT：从最右边开始，这意味着最左边的像素可能会丢失
          //默认CENTER：居中，这个时候左右都有一定的像素会丢失
          if ((data.centerCropGravity & Gravity.LEFT) == Gravity.LEFT) {
            drawX = 0;
          } else if ((data.centerCropGravity & Gravity.RIGHT) == Gravity.RIGHT) {
            drawX = inWidth - newSize;
          } else {
            drawX = (inWidth - newSize) / 2;
          }
          drawWidth = newSize;
          scaleX = targetWidth / (float) drawWidth;
          scaleY = heightRatio;
        } else {
          drawX = 0;
          drawWidth = inWidth;
          scaleX = scaleY = heightRatio;
        }
        if (shouldResize(onlyScaleDown, inWidth, inHeight, targetWidth, targetHeight)) {
          //在比例不一致的情况下，CenterCrop先会进行裁剪，然后最终按照比例进行缩放
          matrix.preScale(scaleX, scaleY);
        }
      } else if (data.centerInside) {
        // Keep aspect ratio if one dimension is set to 0
        float widthRatio =
            targetWidth != 0 ? targetWidth / (float) inWidth : targetHeight / (float) inHeight;
        float heightRatio =
            targetHeight != 0 ? targetHeight / (float) inHeight : targetWidth / (float) inWidth;
        float scale = widthRatio < heightRatio ? widthRatio : heightRatio;
        if (shouldResize(onlyScaleDown, inWidth, inHeight, targetWidth, targetHeight)) {
          matrix.preScale(scale, scale);
        }
      } else if ((targetWidth != 0 || targetHeight != 0) //
          && (targetWidth != inWidth || targetHeight != inHeight)) {
        // If an explicit target size has been specified and they do not match the results bounds,
        // pre-scale the existing matrix appropriately.
        // Keep aspect ratio if one dimension is set to 0.
        float sx =
            targetWidth != 0 ? targetWidth / (float) inWidth : targetHeight / (float) inHeight;
        float sy =
            targetHeight != 0 ? targetHeight / (float) inHeight : targetWidth / (float) inWidth;
        if (shouldResize(onlyScaleDown, inWidth, inHeight, targetWidth, targetHeight)) {
          matrix.preScale(sx, sy);
        }
      }
    }

    Bitmap newResult =
        Bitmap.createBitmap(result, drawX, drawY, drawWidth, drawHeight, matrix, true);
    if (newResult != result) {
      result.recycle();
      result = newResult;
    }

    return result;
  }

  private static boolean shouldResize(boolean onlyScaleDown, int inWidth, int inHeight,
      int targetWidth, int targetHeight) {
    //onlyScaleDown：true
    //此时只有当Bitmap宽高大于目标宽高的时候才会进行矩阵缩放处理
    //默认false，意味着都要进行矩阵缩放/拉伸处理
    return !onlyScaleDown || (targetWidth != 0 && inWidth > targetWidth)
            || (targetHeight != 0 && inHeight > targetHeight);
  }

  static int getExifRotation(int orientation) {
    int rotation;
    switch (orientation) {
      case ORIENTATION_ROTATE_90:
      case ORIENTATION_TRANSPOSE:
        rotation = 90;
        break;
      case ORIENTATION_ROTATE_180:
      case ORIENTATION_FLIP_VERTICAL:
        rotation = 180;
        break;
      case ORIENTATION_ROTATE_270:
      case ORIENTATION_TRANSVERSE:
        rotation = 270;
        break;
      default:
        rotation = 0;
    }
    return rotation;
  }

 static int getExifTranslation(int orientation)  {
    int translation;
    switch (orientation) {
      case ORIENTATION_FLIP_HORIZONTAL:
      case ORIENTATION_FLIP_VERTICAL:
      case ORIENTATION_TRANSPOSE:
      case ORIENTATION_TRANSVERSE:
        translation = -1;
        break;
      default:
        translation = 1;
    }
    return translation;
  }
}

