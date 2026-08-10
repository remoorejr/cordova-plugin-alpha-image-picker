/*global cordova, window, console */
/**
 * An Image Picker plugin for Cordova
 *
 * Developed by Wymsee for Sync OnSet
 */

var ImagePicker = function() {};

ImagePicker.prototype.OutputType = {
  FILE_URI: 0,
  BASE64_STRING: 1
};

ImagePicker.prototype.validateOutputType = function (options) {
  var outputType = options.outputType;
  var valid = (outputType === this.OutputType.FILE_URI || outputType === this.OutputType.BASE64_STRING);

  if (!valid) {
    console.log(
      'Invalid outputType. Defaulting to FILE_URI. ' +
      'Use window.imagePicker.OutputType.FILE_URI or BASE64_STRING.'
    );
    options.outputType = this.OutputType.FILE_URI;
  }
};

ImagePicker.prototype.hasReadPermission = function (callback) {
  return cordova.exec(callback, null, "ImagePicker", "hasReadPermission", []);
};

ImagePicker.prototype.requestReadPermission = function (callback, failureCallback) {
  return cordova.exec(callback, failureCallback, "ImagePicker", "requestReadPermission", []);
};

/*
 * success - success callback
 * fail - error callback
 * options
 *   .maximumImagesCount - max images to be selected, defaults to 15
 *   .width - width to resize image to
 *   .height - height to resize image to
 *   .quality - quality of resized image, defaults to 100
 *   .outputType - output returned. defaults to FILE_URI.
 *   .allow_video - include videos (platform dependent)
 *   .title - picker title
 *   .message - picker prompt/message
 *   .disable_popover - iOS popover behavior
 */
ImagePicker.prototype.getPictures = function (success, fail, options) {
  options = options || {};

  var params = {
    maximumImagesCount: (typeof options.maximumImagesCount === 'number') ? options.maximumImagesCount : 15,
    width: (typeof options.width === 'number') ? options.width : 0,
    height: (typeof options.height === 'number') ? options.height : 0,
    quality: (typeof options.quality === 'number') ? options.quality : 100,
    allow_video: !!options.allow_video,
    title: (typeof options.title === 'string') ? options.title : 'Select an Album',
    message: (typeof options.message === 'string') ? options.message : null,
    outputType: (options.outputType === 0 || options.outputType === 1) ? options.outputType : this.OutputType.FILE_URI,
    disable_popover: !!options.disable_popover
  };

  this.validateOutputType(params);

  return cordova.exec(success, fail, "ImagePicker", "getPictures", [params]);
};

/**
 * Convenience helper: ensures permission before opening picker.
 * This avoids caller-side race/flow mistakes.
 */
ImagePicker.prototype.getPicturesWithPermission = function (success, fail, options) {
  var self = this;

  self.hasReadPermission(function (hasPermission) {
    if (hasPermission) {
      self.getPictures(success, fail, options);
      return;
    }

    self.requestReadPermission(function () {
      // Permission granted (or already granted by the time callback returns)
      self.getPictures(success, fail, options);
    }, fail);
  }, fail);
};

window.imagePicker = new ImagePicker();