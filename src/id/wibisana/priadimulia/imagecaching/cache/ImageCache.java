/*
 * Copyright 2012 Google Inc.
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

package id.wibisana.priadimulia.imagecaching.cache;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.support.v4.util.LruCache;

public class ImageCache {
	private static final float DEFAULT_MEM_CACHE_PERCENT = 0.15f;
	private static final int DEFAULT_DISK_CACHE_SIZE = 1024 * 1024 * 10; // 10MB
	private static final String DEFAULT_DISK_CACHE_DIR = "images";
	private static final CompressFormat DEFAULT_COMPRESS_FORMAT = CompressFormat.JPEG;
	private static final int DEFAULT_COMPRESS_QUALITY = 75;
	private static final int DISK_CACHE_INDEX = 0;
	private static final boolean DEFAULT_MEM_CACHE_ENABLED = true;
	private static final boolean DEFAULT_DISK_CACHE_ENABLED = true;
	private static final boolean DEFAULT_CLEAR_DISK_CACHE_ON_START = false;
	private static final boolean DEFAULT_INIT_DISK_CACHE_ON_CREATE = true;

	private DiskLruCache mDiskLruCache;
	private LruCache<String, Bitmap> mMemoryCache;
	private ImageCacheParams mCacheParams;
	private final Object mDiskCacheLock = new Object();
	private boolean mDiskCacheStarting = true;
	
	public static ImageCache sImageCache;
	
	public static ImageCache get(Context context) {
		if(sImageCache == null) {
			initialize(context);
		}
		return sImageCache;
	}
	
	public static void initialize(Context context) {
		if(sImageCache == null) {
			sImageCache = new ImageCache(context);
		}
	}
	
	public ImageCache(ImageCacheParams cacheParams) {
		init(cacheParams);
	}

	public ImageCache(Context context) {
		init(new ImageCacheParams(context));
	}

	private void init(ImageCacheParams cacheParams) {
		mCacheParams = cacheParams;
		if (mCacheParams.memoryCacheEnabled) {
			mMemoryCache = new LruCache<String, Bitmap>(
					mCacheParams.memCacheSize) {
				@Override
				protected int sizeOf(String key, Bitmap bitmap) {
					return getBitmapSize(bitmap);
				}
			};
		}
		if (cacheParams.initDiskCacheOnCreate) {
			initDiskCache();
		}
	}

	private void initDiskCache() {
		final InitDiskCacheTask initDiskCacheTask = new InitDiskCacheTask();
		initDiskCacheTask.execute();
	}
	
	private class InitDiskCacheTask extends AsyncTask<Void, Void, Void> {
	    @Override
	    protected Void doInBackground(Void... params) {
	    	synchronized (mDiskCacheLock) {
				if (mDiskLruCache == null || mDiskLruCache.isClosed()) {
					File diskCacheDir = mCacheParams.diskCacheDir;
					if (mCacheParams.diskCacheEnabled && diskCacheDir != null) {
						if (!diskCacheDir.exists()) {
							diskCacheDir.mkdirs();
						}
						if (getUsableSpace(diskCacheDir) > mCacheParams.diskCacheSize) {

							try {
								mDiskLruCache = DiskLruCache.open(diskCacheDir, 1,
										1, mCacheParams.diskCacheSize);
							} catch (final IOException e) {
								mCacheParams.diskCacheDir = null;
							}
						}
					}
				}
				mDiskCacheStarting = false;
				mDiskCacheLock.notifyAll();
			}
			return null;
	    }
	}
	
	public void addBitmapToMemCache(String data, Bitmap bitmap) {
		if (data == null || bitmap == null) {
			return;
		}
		if (mMemoryCache != null && mMemoryCache.get(data) == null) {
			mMemoryCache.put(data, bitmap);
		}
	}

	public void addBitmapToCache(String data, Bitmap bitmap) {
		if (data == null || bitmap == null) {
			return;
		}
		addBitmapToMemCache(data, bitmap);
		synchronized (mDiskCacheLock) {
			if (mDiskLruCache != null) {
				final String key = hashKeyForDisk(data);
				OutputStream out = null;
				try {
					DiskLruCache.Snapshot snapshot = mDiskLruCache.get(key);
					if (snapshot == null) {
						final DiskLruCache.Editor editor = mDiskLruCache
								.edit(key);
						if (editor != null) {
							out = editor.newOutputStream(DISK_CACHE_INDEX);
							bitmap.compress(mCacheParams.compressFormat,
									mCacheParams.compressQuality, out);
							editor.commit();
							out.close();
						}
					} else {
						snapshot.getInputStream(DISK_CACHE_INDEX).close();
					}
				} catch (final IOException e) {
					e.printStackTrace();
				} catch (Exception e) {
					e.printStackTrace();
				} finally {
					try {
						if (out != null) {
							out.close();
						}

					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			}
		}
	}

	public Bitmap getBitmapFromMemCache(String data) {
		if (mMemoryCache != null) {
			final Bitmap memBitmap = mMemoryCache.get(data);
			if (memBitmap != null) {
				return memBitmap;
			}
		}
		return null;
	}

	public Bitmap getBitmapFromDiskCache(String data) {
		final String key = hashKeyForDisk(data);
		synchronized (mDiskCacheLock) {
			while (mDiskCacheStarting) {
				try {
					mDiskCacheLock.wait();
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
			if (mDiskLruCache != null) {
				InputStream inputStream = null;
				try {
					final DiskLruCache.Snapshot snapshot = mDiskLruCache
							.get(key);
					if (snapshot != null) {
						inputStream = snapshot.getInputStream(DISK_CACHE_INDEX);
						if (inputStream != null) {
							final Bitmap bitmap = BitmapFactory
									.decodeStream(inputStream);
							return bitmap;
						}
					}

				} catch (final IOException e) {
					e.printStackTrace();
				} finally {
					try {
						if (inputStream != null) {
							inputStream.close();
						}
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			}
			return null;
		}
	}

	public void clearCache() {
		final ClearCacheTask clearCacheTask = new ClearCacheTask();
		clearCacheTask.execute();
	}
	
	private class ClearCacheTask extends AsyncTask<Void, Void, Void> {
		@Override
		protected Void doInBackground(Void... params) {
			if (mMemoryCache != null) {
				mMemoryCache.evictAll();
			}
			synchronized (mDiskCacheLock) {
				mDiskCacheStarting = true;
				if (mDiskLruCache != null && !mDiskLruCache.isClosed()) {
					try {
						mDiskLruCache.delete();
					} catch (IOException e) {
						e.printStackTrace();
					}
					mDiskLruCache = null;
					initDiskCache();
				}
			}
			return null;
		}
		
	}

	public void flush() {
		FlushTask flushTask = new FlushTask();
		flushTask.execute();
	}
	
	private class FlushTask extends AsyncTask<Void, Void, Void> {
		@Override
		protected Void doInBackground(Void... params) {
			synchronized (mDiskCacheLock) {
				if (mDiskLruCache != null) {
					try {
						mDiskLruCache.flush();
					} catch (IOException e) {
					}
				}
			}
			return null;
		}
		
	}

	public void close() {
		final CloseTask closeTask = new CloseTask();
		closeTask.execute();
	}
	
	private class CloseTask extends AsyncTask<Void, Void, Void> {
		@Override
		protected Void doInBackground(Void... params) {
			synchronized (mDiskCacheLock) {
				if (mDiskLruCache != null) {
					try {
						if (!mDiskLruCache.isClosed()) {
							mDiskLruCache.close();
							mDiskLruCache = null;
						}
					} catch (IOException e) {
					}
				}
			}
			return null;
		}
		
	}

	public static class ImageCacheParams {
		public int memCacheSize;
		public int diskCacheSize = DEFAULT_DISK_CACHE_SIZE;
		public File diskCacheDir;
		public CompressFormat compressFormat = DEFAULT_COMPRESS_FORMAT;
		public int compressQuality = DEFAULT_COMPRESS_QUALITY;
		public boolean memoryCacheEnabled = DEFAULT_MEM_CACHE_ENABLED;
		public boolean diskCacheEnabled = DEFAULT_DISK_CACHE_ENABLED;
		public boolean clearDiskCacheOnStart = DEFAULT_CLEAR_DISK_CACHE_ON_START;
		public boolean initDiskCacheOnCreate = DEFAULT_INIT_DISK_CACHE_ON_CREATE;

		public ImageCacheParams(Context context) {
			init(context, getDiskCacheDir(context, DEFAULT_DISK_CACHE_DIR));
		}

		public ImageCacheParams(Context context, String uniqueName) {
			init(context, getDiskCacheDir(context, uniqueName));
		}

		public ImageCacheParams(Context context, File diskCacheDir) {
			init(context, diskCacheDir);
		}

		private void init(Context context, File diskCacheDir) {
			setMemCacheSizePercent(context, DEFAULT_MEM_CACHE_PERCENT);
			this.diskCacheDir = diskCacheDir;
		}

		public void setMemCacheSizePercent(Context context, float percent) {
			if (percent < 0.05f || percent > 0.8f) {
				throw new IllegalArgumentException(
						"setMemCacheSizePercent - percent must be "
								+ "between 0.05 and 0.8 (inclusive)");
			}
			memCacheSize = Math.round(percent * getMemoryClass(context) * 1024
					* 1024);
		}

		private static int getMemoryClass(Context context) {
			return ((ActivityManager) context
					.getSystemService(Context.ACTIVITY_SERVICE))
					.getMemoryClass();
		}
	}

	public static File getDiskCacheDir(Context context, String uniqueName) {
		final String cachePath = Environment.MEDIA_MOUNTED.equals(Environment
				.getExternalStorageState()) || !isExternalStorageRemovable() ? getExternalCacheDir(
				context).getPath()
				: context.getCacheDir().getPath();

		return new File(cachePath + File.separator + uniqueName);
	}

	public static String hashKeyForDisk(String key) {
		String cacheKey;
		try {
			final MessageDigest mDigest = MessageDigest.getInstance("MD5");
			mDigest.update(key.getBytes());
			cacheKey = bytesToHexString(mDigest.digest());
		} catch (NoSuchAlgorithmException e) {
			cacheKey = String.valueOf(key.hashCode());
		}
		return cacheKey;
	}

	private static String bytesToHexString(byte[] bytes) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < bytes.length; i++) {
			String hex = Integer.toHexString(0xFF & bytes[i]);
			if (hex.length() == 1) {
				sb.append('0');
			}
			sb.append(hex);
		}
		return sb.toString();
	}

	@TargetApi(12)
	public static int getBitmapSize(Bitmap bitmap) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB_MR1) {
			return bitmap.getByteCount();
		}

		return bitmap.getRowBytes() * bitmap.getHeight();
	}

	@TargetApi(9)
	public static boolean isExternalStorageRemovable() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
			return Environment.isExternalStorageRemovable();
		}
		return true;
	}

	@TargetApi(8)
	public static File getExternalCacheDir(Context context) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.FROYO) {
			return context.getExternalCacheDir();
		}

		final String cacheDir = "/Android/data/" + context.getPackageName()
				+ "/cache/";
		return new File(Environment.getExternalStorageDirectory().getPath()
				+ cacheDir);
	}

	@SuppressWarnings("deprecation")
	@TargetApi(9)
	public static long getUsableSpace(File path) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
			return path.getUsableSpace();
		}
		final StatFs stats = new StatFs(path.getPath());
		return (long) stats.getBlockSize() * (long) stats.getAvailableBlocks();
	}

}
