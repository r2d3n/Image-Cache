package id.wibisana.priadimulia.imagecaching.bitmap;

import id.wibisana.priadimulia.imagecaching.cache.ImageCache;

import java.io.File;
import java.lang.ref.WeakReference;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.widget.ImageView;

public class ComicMangaBitmap {

	private Resources mResources;
	private String mImagePath;
	private Context mContext;
	private boolean isCaching;

	public ComicMangaBitmap(Resources resources, String image, Context context, boolean caching) {
		mResources = resources;
		mImagePath = image;
		mContext = context;
		isCaching = caching;
	}

	private Bitmap decodeSampledBitmapFromResource(int reqWidth, int reqHeight) {
		final BitmapFactory.Options options = new BitmapFactory.Options();

		options.inSampleSize = calculateInSampleSize(reqWidth, reqHeight, options);
		options.inJustDecodeBounds = false;

		return BitmapFactory.decodeFile(mImagePath, options);
	}

	private int calculateInSampleSize(int reqWidth, int reqHeight, BitmapFactory.Options options) {
		int inSampleSize = 1;
		options.inJustDecodeBounds = true;

		BitmapFactory.decodeFile(mImagePath, options);

		if (options.outHeight > reqHeight || options.outWidth > reqWidth) {
			final int halfHeight = options.outHeight / 2;
			final int halfWidth = options.outWidth / 2;

			while ((halfHeight / inSampleSize) > reqHeight && (halfWidth / inSampleSize) > reqWidth) {
				inSampleSize *= 2;
			}
		}

		return inSampleSize;
	}

	private class AsyncDrawable extends BitmapDrawable {
		private final WeakReference<BitmapWorkerTask> bitmapWorkerTaskReference;

		public AsyncDrawable(BitmapWorkerTask bitmapWorkerTask) {
			super(mResources, BitmapFactory.decodeResource(mResources, R.drawable.dummy));
			bitmapWorkerTaskReference = new WeakReference<BitmapWorkerTask>(bitmapWorkerTask);
		}

		public BitmapWorkerTask getBitmapWorkerTask() {
			return bitmapWorkerTaskReference.get();
		}
	}

	private class BitmapWorkerTask extends AsyncTask<Integer, Void, Bitmap> {
		private final WeakReference<ImageView> imageViewReference;

		public BitmapWorkerTask(ImageView imageView) {
			imageViewReference = new WeakReference<ImageView>(imageView);
		}

		public String getImage() {
			return mImagePath;
		}

		@Override
		protected Bitmap doInBackground(Integer... dimensions) {
			Bitmap bitmap = null;

			if(isCaching) {
				bitmap = ImageCache.get(mContext).getBitmapFromMemCache(mImagePath);

				if(bitmap == null) {
					bitmap = ImageCache.get(mContext).getBitmapFromDiskCache(mImagePath);

					if(bitmap != null) {
						ImageCache.get(mContext).addBitmapToMemCache(mImagePath, bitmap);
					}
				}
			}

			if(bitmap == null) {
				final File file = new File(mImagePath);

				if(!file.exists()) {
					return null;
				}

				bitmap = decodeSampledBitmapFromResource(dimensions[0], dimensions[1]);
				
				if(isCaching) {
					ImageCache.get(mContext).addBitmapToCache(mImagePath, bitmap);
				}
			}

			return bitmap;
		}

		@Override
		protected void onPostExecute(Bitmap bitmap) {
			if (isCancelled()) {
				bitmap = null;
			}

			if (imageViewReference != null && bitmap != null) {
				final ImageView imageView = imageViewReference.get();
				final BitmapWorkerTask bitmapWorkerTask = getBitmapWorkerTask(imageView);

				if (this == bitmapWorkerTask && imageView != null) {
					imageView.setImageBitmap(bitmap);
				}
			}

		}
	}

	public void loadBitmap(int reqWidth, int reqHeight, ImageView imageView) {
		if (cancelPotentialWork(imageView)) {
			final BitmapWorkerTask task = new BitmapWorkerTask(imageView);
			final AsyncDrawable asyncDrawable = new AsyncDrawable(task);

			imageView.setImageDrawable(asyncDrawable);
			task.execute(reqWidth, reqHeight);
		}
	}

	private boolean cancelPotentialWork(ImageView imageView) {
		final BitmapWorkerTask bitmapWorkerTask = getBitmapWorkerTask(imageView);

		if (bitmapWorkerTask != null) {
			final String image = bitmapWorkerTask.getImage();

			if (image != mImagePath) {
				bitmapWorkerTask.cancel(true);
			} else {
				return false;
			}
		}

		return true;
	}

	private BitmapWorkerTask getBitmapWorkerTask(ImageView imageView) {
		if (imageView != null) {
			final Drawable drawable = imageView.getDrawable();

			if (drawable instanceof AsyncDrawable) {
				final AsyncDrawable asyncDrawable = (AsyncDrawable) drawable;
				return asyncDrawable.getBitmapWorkerTask();
			}
		}

		return null;
	}

}
