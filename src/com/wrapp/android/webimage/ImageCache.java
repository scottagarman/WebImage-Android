/*
 * Copyright (c) 2011 Bohemian Wrappsody AB
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.  IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.wrapp.android.webimage;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.*;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class ImageCache {
  private static final long ONE_DAY_IN_SEC = 24 * 60 * 60;
  private static final long CACHE_RECHECK_AGE_IN_SEC = ONE_DAY_IN_SEC;
  private static final long CACHE_RECHECK_AGE_IN_MS = CACHE_RECHECK_AGE_IN_SEC * 1000;
  private static final long CACHE_EXPIRATION_AGE_IN_SEC = ONE_DAY_IN_SEC * 30;
  private static final String DEFAULT_CACHE_SUBDIRECTORY_NAME = "images";

  private static File cacheDirectory;
  private static Map<String, WeakReference<Bitmap>> memoryCache = new HashMap<String, WeakReference<Bitmap>>();

  public static boolean isImageCached(URL imageUrl) {
    final String imageKey = getKeyForUrl(imageUrl);
    final File cacheFile = new File(getCacheDirectory(), imageKey);
    return cacheFile.exists();
  }

  public static Bitmap loadImage(ImageRequest request) {
    final String imageKey = getKeyForUrl(request.imageUrl);
    LogWrapper.logMessage("Loading image: " + request.imageUrl);

    // Always check the memory cache first, even if the caller doesn't request this image to be cached
    // there. This lookup is pretty fast, so it's a good performance gain.
    Bitmap bitmap = loadImageFromMemoryCache(imageKey);
    if(bitmap != null) {
      LogWrapper.logMessage("Found image " + request.imageUrl + " in memory cache");
      return bitmap;
    }

    bitmap = loadImageFromFileCache(imageKey, request.imageUrl, request.loadOptions);
    if(bitmap != null) {
      LogWrapper.logMessage("Found image " + request.imageUrl + " in file cache");
      return bitmap;
    }

    if(ImageDownloader.loadImage(imageKey, request.imageUrl)) {
      bitmap = loadImageFromFileCache(imageKey, request.imageUrl, request.loadOptions);
      if(bitmap != null) {
        if(request.cacheInMemory) {
          saveImageInMemoryCache(imageKey, bitmap);
        }
        return bitmap;
      }
    }

    LogWrapper.logMessage("Could not load drawable, returning null");
    return bitmap;
  }

  private static Bitmap loadImageFromMemoryCache(final String imageKey) {
    synchronized(memoryCache) {
      if(memoryCache.containsKey(imageKey)) {
        // Apparently Android's SoftReference can sometimes free objects too early, see:
        // http://groups.google.com/group/android-developers/browse_thread/thread/ebabb0dadf38acc1
        // If that happens then it's no big deal, as this class will simply re-load the image
        // from file, but if that is the case then we should be polite and remove the imageKey
        // from the cache to reflect the actual caching state of this image.
        final Bitmap bitmap = memoryCache.get(imageKey).get();
        if(bitmap == null) {
          memoryCache.remove(imageKey);
        }
        return bitmap;
      }
      else {
        return null;
      }
    }
  }

  private static Bitmap loadImageFromFileCache(final String imageKey, final URL imageUrl, BitmapFactory.Options options) {
    Bitmap bitmap = null;

    File cacheFile = new File(getCacheDirectory(), imageKey);
    if(cacheFile.exists()) {
      try {
        Date now = new Date();
        long fileAgeInMs = now.getTime() - cacheFile.lastModified();
        if(fileAgeInMs > CACHE_RECHECK_AGE_IN_MS) {
          Date expirationDate = ImageDownloader.getServerTimestamp(imageUrl);
          if(expirationDate.after(now)) {
            // TODO: decodeFileDescriptor might be faster, see http://stackoverflow.com/a/7116158/14302
            bitmap = BitmapFactory.decodeStream(new FileInputStream(cacheFile), null, options);
            LogWrapper.logMessage("Cached version of " + imageUrl.toString() + " is still current, updating timestamp");
            if(!cacheFile.setLastModified(now.getTime())) {
              // Ugh, it seems that in some cases this call will always return false and refuse to update the timestamp
              // For more info, see: http://code.google.com/p/android/issues/detail?id=18624
              // In these cases, we manually re-write the file to disk. Yes, that sucks, but it's better than loosing
              // the ability to do any intelligent file caching at all.
              // TODO: saveImageInFileCache(imageKey, bitmap);
            }
          }
          else {
            LogWrapper.logMessage("Cached version of " + imageUrl.toString() + " found, but has expired.");
          }
        }
        else {
          // TODO: decodeFileDescriptor might be faster, see http://stackoverflow.com/a/7116158/14302
          bitmap = BitmapFactory.decodeStream(new FileInputStream(cacheFile), null, options);
          if(bitmap == null) {
            throw new Exception("Could not create bitmap from image: " + imageUrl.toString());
          }
        }
      }
      catch(Exception e) {
        LogWrapper.logException(e);
      }
    }

    return bitmap;
  }

  private static void saveImageInMemoryCache(String imageKey, final Bitmap bitmap) {
    synchronized(memoryCache) {
      memoryCache.put(imageKey, new WeakReference<Bitmap>(bitmap));
    }
  }

  public static void saveImageInFileCache(String imageKey, final Bitmap bitmap) {
    OutputStream outputStream = null;

    try {
      File cacheFile = new File(getCacheDirectory(), imageKey);
      outputStream = new FileOutputStream(cacheFile);
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream);
      LogWrapper.logMessage("Saved image " + imageKey + " to file cache");
      outputStream.flush();
    }
    catch(IOException e) {
      LogWrapper.logException(e);
    }
    finally {
      try {
        if(outputStream != null) {
          outputStream.close();
        }
      }
      catch(IOException e) {
        LogWrapper.logException(e);
      }
    }
  }


  private static File getCacheDirectory() {
    if(cacheDirectory == null) {
      //noinspection NullableProblems
      setCacheDirectory(null, DEFAULT_CACHE_SUBDIRECTORY_NAME);
    }
    return cacheDirectory;
  }

  public static void setCacheDirectory(String packageName, String subdirectoryName) {
    final File androidDirectory = new File(android.os.Environment.getExternalStorageDirectory(), "Android");
    if(!androidDirectory.exists()) {
      androidDirectory.mkdir();
    }

    final File dataDirectory = new File(androidDirectory, "data");
    if(!dataDirectory.exists()) {
      dataDirectory.mkdir();
    }

    // If package name is null, then use this package name instead
    if(packageName == null) {
      packageName = ImageCache.class.getPackage().getName();
    }

    final File packageDirectory = new File(dataDirectory, packageName);
    if(!packageDirectory.exists()) {
      packageDirectory.mkdir();
    }

    cacheDirectory = new File(packageDirectory, subdirectoryName);
    if(!cacheDirectory.exists()) {
      cacheDirectory.mkdir();
    }

    if(packageName != null) {
      // WebImage 1.1.2 and earlier stored images in /mnt/sdcard/data/packageName. If images are found there,
      // we should migrate them to the correct location. Unfortunately, WebImage 1.1.2 and below also used
      // the location /mnt/sdcard/data/images if no packageName was supplied. Since this isn't very specific,
      // we don't bother to remove those images, as they may belong to other applications.
      final File oldDataDirectory = new File(android.os.Environment.getExternalStorageDirectory(), "data");
      final File oldPackageDirectory = new File(oldDataDirectory, packageName);
      final File oldCacheDirectory = new File(oldPackageDirectory, subdirectoryName);
      if(oldCacheDirectory.exists()) {
        if(cacheDirectory.delete()) {
          if(!oldCacheDirectory.renameTo(cacheDirectory)) {
            LogWrapper.logMessage("Could not migrate old image directory");
          }
        }
      }
    }
  }

  /**
   * Clear expired images in the file cache to save disk space. This method will remove all
   * images older than {@link #CACHE_EXPIRATION_AGE_IN_SEC} seconds.
   */
  public static void clearOldCacheFiles() {
    clearOldCacheFiles(CACHE_EXPIRATION_AGE_IN_SEC);
  }

  /**
   * Clear all images older than a given amount of seconds.
   * @param cacheAgeInSec Image expiration limit, in seconds
   */
  public static void clearOldCacheFiles(long cacheAgeInSec) {
    final long cacheAgeInMs = cacheAgeInSec * 1000;
    Date now = new Date();
    String[] cacheFiles = getCacheDirectory().list();
    if(cacheFiles != null) {
      for(String child : cacheFiles) {
        File childFile = new File(getCacheDirectory(), child);
        if(childFile.isFile()) {
          long fileAgeInMs = now.getTime() - childFile.lastModified();
          if(fileAgeInMs > cacheAgeInMs) {
            LogWrapper.logMessage("Deleting image '" + child + "' from cache");
            childFile.delete();
          }
        }
      }
    }
  }

  /**
   * Remove all images from the fast in-memory cache. This should be called to free up memory
   * when receiving onLowMemory warnings or when the activity knows it has no use for the items
   * in the memory cache anymore.
   */
  public static void clearMemoryCaches() {
    if(memoryCache != null) {
      synchronized(memoryCache) {
        LogWrapper.logMessage("Emptying in-memory cache");
        for(String key : memoryCache.keySet()) {
          WeakReference reference = memoryCache.get(key);
          if(reference != null) {
            reference.clear();
          }
        }
        memoryCache.clear();
      }
    }
  }


  private static final char[] HEX_CHARACTERS = {
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
  };

  /**
   * Calculate a hash key for the given URL, which is used to create safe filenames and
   * key strings. Internally, this method uses MD5, as that is available on Android 2.1
   * devices (unlike base64, for example).
   * @param url Image URL
   * @return Hash for image URL
   */
  public static String getKeyForUrl(URL url) {
    String result = "";

    try {
      String urlString = url.toString();
      MessageDigest digest = MessageDigest.getInstance("MD5");
      digest.update(urlString.getBytes(), 0, urlString.length());
      byte[] resultBytes = digest.digest();
      StringBuilder hexStringBuilder = new StringBuilder(2 * resultBytes.length);
      for(final byte b : resultBytes) {
        hexStringBuilder.append(HEX_CHARACTERS[(b & 0xf0) >> 4]).append(HEX_CHARACTERS[b & 0x0f]);
      }
      result = hexStringBuilder.toString();
    }
    catch(NoSuchAlgorithmException e) {
      LogWrapper.logException(e);
    }

    return result;
  }
}
