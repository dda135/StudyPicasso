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

import android.support.annotation.VisibleForTesting;
import android.view.View;
import android.view.View.OnAttachStateChangeListener;
import android.view.ViewTreeObserver;
import android.view.ViewTreeObserver.OnPreDrawListener;
import android.widget.ImageView;
import java.lang.ref.WeakReference;

/**
 * 延迟加载
 */
class DeferredRequestCreator implements OnPreDrawListener, OnAttachStateChangeListener {
  private final RequestCreator creator;
  @VisibleForTesting final WeakReference<ImageView> target;
  @VisibleForTesting Callback callback;

  DeferredRequestCreator(RequestCreator creator, ImageView target, Callback callback) {
    this.creator = creator;
    this.target = new WeakReference<>(target);
    this.callback = callback;

    //稍微注意一下在preDraw当中返回true的时候将不会开始draw流程
    //要注意保证Observer不要泄露和重复添加，这里采用的是attach添加以及detach移除
    //这里为了处理RecyclerView中有时候没有进行图片加载
    target.addOnAttachStateChangeListener(this);

    // Only add the pre-draw listener if the view is already attached.
    // See: https://github.com/square/picasso/issues/1321
    // 只有在已经关联到window的情况下视图树才会有当前ImageView
    if (target.getWindowToken() != null) {
      onViewAttachedToWindow(target);
    }
  }

  @Override public void onViewAttachedToWindow(View view) {
    view.getViewTreeObserver().addOnPreDrawListener(this);
  }

  @Override public void onViewDetachedFromWindow(View view) {
    view.getViewTreeObserver().removeOnPreDrawListener(this);
  }

  @Override public boolean onPreDraw() {
    ImageView target = this.target.get();
    if (target == null) {
      return true;
    }

    ViewTreeObserver vto = target.getViewTreeObserver();
    //这里有警告，当前视图树如果失效，而回调已经开始，此时应该在内部进行判断，从而避免可能出现的异常
    if (!vto.isAlive()) {
      return true;
    }
    //在preDraw回调中，ImageView是已经完成测量和绘制，此时可以获得正确的宽高
    int width = target.getWidth();
    int height = target.getHeight();

    if (width <= 0 || height <= 0 || target.isLayoutRequested()) {
      return true;
    }

    target.removeOnAttachStateChangeListener(this);
    vto.removeOnPreDrawListener(this);
    this.target.clear();
    //此时ImageView可以正确获得高宽，并且发起请求，则会进行压缩处理
    this.creator.unfit().resize(width, height).into(target, callback);
    return true;
  }

  void cancel() {
    creator.clearTag();
    callback = null;

    ImageView target = this.target.get();
    if (target == null) {
      return;
    }
    this.target.clear();

    target.removeOnAttachStateChangeListener(this);

    ViewTreeObserver vto = target.getViewTreeObserver();
    if (vto.isAlive()) {
      vto.removeOnPreDrawListener(this);
    }
  }

  Object getTag() {
    return creator.getTag();
  }
}
