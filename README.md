Image-Cache
===========

This is an android image cache source file.  
It based on iosched source (http://code.google.com/p/iosched/)  
and i modified it a bit to fit my need.  
  
Usage:  
Add: ImageCache.initialize(this); inside onCreate method in your activity.  
  
CustomBitmap customBitmap = new CustomBitmap(resources, imagePath, activity, true);  
customBitmap.loadBitmap(width, height, imageView);