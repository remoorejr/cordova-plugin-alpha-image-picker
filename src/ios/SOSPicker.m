#import "SOSPicker.h"
#import "GMImagePickerController.h"
#import "GMFetchItem.h"
#import <Photos/Photos.h>

#define CDV_PHOTO_PREFIX @"cdv_photo_"

typedef enum : NSUInteger {
    FILE_URI = 0,
    BASE64_STRING = 1
} SOSPickerOutputType;

@interface SOSPicker () <GMImagePickerControllerDelegate>
@end

@implementation SOSPicker

@synthesize callbackId;

- (BOOL)isAuthorizedStatus:(PHAuthorizationStatus)status {
    if (@available(iOS 14, *)) {
        return (status == PHAuthorizationStatusAuthorized || status == PHAuthorizationStatusLimited);
    }
    return (status == PHAuthorizationStatusAuthorized);
}

- (void)hasReadPermission:(CDVInvokedUrlCommand *)command {
    PHAuthorizationStatus status;
    if (@available(iOS 14, *)) {
        status = [PHPhotoLibrary authorizationStatusForAccessLevel:PHAccessLevelReadWrite];
    } else {
        status = [PHPhotoLibrary authorizationStatus];
    }

    BOOL granted = [self isAuthorizedStatus:status];
    CDVPluginResult* pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsBool:granted];
    [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
}

- (void)requestReadPermission:(CDVInvokedUrlCommand *)command {
    PHAuthorizationStatus status;
    if (@available(iOS 14, *)) {
        status = [PHPhotoLibrary authorizationStatusForAccessLevel:PHAccessLevelReadWrite];
    } else {
        status = [PHPhotoLibrary authorizationStatus];
    }

    if ([self isAuthorizedStatus:status]) {
        CDVPluginResult* ok = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
        [self.commandDelegate sendPluginResult:ok callbackId:command.callbackId];
        return;
    }

    if (status == PHAuthorizationStatusDenied || status == PHAuthorizationStatusRestricted) {
        NSString* message = @"Photo access denied/restricted. Enable Photos permission in iOS Settings.";
        CDVPluginResult* err = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:message];
        [self.commandDelegate sendPluginResult:err callbackId:command.callbackId];
        return;
    }

    if (status == PHAuthorizationStatusNotDetermined) {
        if (@available(iOS 14, *)) {
            [PHPhotoLibrary requestAuthorizationForAccessLevel:PHAccessLevelReadWrite handler:^(PHAuthorizationStatus newStatus) {
                dispatch_async(dispatch_get_main_queue(), ^{
                    if ([self isAuthorizedStatus:newStatus]) {
                        CDVPluginResult* ok = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
                        [self.commandDelegate sendPluginResult:ok callbackId:command.callbackId];
                    } else {
                        NSString* message = @"Photo permission not granted.";
                        CDVPluginResult* err = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:message];
                        [self.commandDelegate sendPluginResult:err callbackId:command.callbackId];
                    }
                });
            }];
        } else {
            [PHPhotoLibrary requestAuthorization:^(PHAuthorizationStatus newStatus) {
                dispatch_async(dispatch_get_main_queue(), ^{
                    if ([self isAuthorizedStatus:newStatus]) {
                        CDVPluginResult* ok = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
                        [self.commandDelegate sendPluginResult:ok callbackId:command.callbackId];
                    } else {
                        NSString* message = @"Photo permission not granted.";
                        CDVPluginResult* err = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:message];
                        [self.commandDelegate sendPluginResult:err callbackId:command.callbackId];
                    }
                });
            }];
        }
    }
}

- (void)getPictures:(CDVInvokedUrlCommand *)command {
    NSDictionary *options = [command.arguments objectAtIndex:0];

    self.outputType = [[options objectForKey:@"outputType"] integerValue];
    BOOL allow_video = [[options objectForKey:@"allow_video"] boolValue];
    NSInteger maximumImagesCount = [[options objectForKey:@"maximumImagesCount"] integerValue];
    NSString *title = [options objectForKey:@"title"];
    NSString *message = [options objectForKey:@"message"];
    BOOL disable_popover = [[options objectForKey:@"disable_popover"] boolValue];

    if (message == (id)[NSNull null]) {
        message = nil;
    }

    self.width = [[options objectForKey:@"width"] integerValue];
    self.height = [[options objectForKey:@"height"] integerValue];
    self.quality = [[options objectForKey:@"quality"] integerValue];
    self.callbackId = command.callbackId;

    [self launchGMImagePicker:allow_video title:title message:message disable_popover:disable_popover maximumImagesCount:maximumImagesCount];
}

- (void)launchGMImagePicker:(bool)allow_video
                      title:(NSString *)title
                    message:(NSString *)message
            disable_popover:(BOOL)disable_popover
         maximumImagesCount:(NSInteger)maximumImagesCount {

    GMImagePickerController *picker = [[GMImagePickerController alloc] init:allow_video];
    picker.delegate = self;
    picker.maximumImagesCount = maximumImagesCount;
    picker.title = title;
    picker.customNavigationBarPrompt = message;
    picker.colsInPortrait = 4;
    picker.colsInLandscape = 6;
    picker.minimumInteritemSpacing = 2.0;

    if (!disable_popover) {
        picker.modalPresentationStyle = UIModalPresentationPopover;
        UIPopoverPresentationController *popPC = picker.popoverPresentationController;
        popPC.permittedArrowDirections = UIPopoverArrowDirectionAny;
        popPC.sourceView = self.viewController.view;
        popPC.sourceRect = self.viewController.view.bounds;
    }

    [self.viewController presentViewController:picker animated:YES completion:nil];
}

- (UIImage*)imageByScalingNotCroppingForSize:(UIImage*)anImage toSize:(CGSize)frameSize {
    UIImage* sourceImage = anImage;
    UIImage* newImage = nil;
    CGSize imageSize = sourceImage.size;
    CGFloat width = imageSize.width;
    CGFloat height = imageSize.height;
    CGFloat targetWidth = frameSize.width;
    CGFloat targetHeight = frameSize.height;
    CGFloat scaleFactor = 0.0;
    CGSize scaledSize = frameSize;

    if (CGSizeEqualToSize(imageSize, frameSize) == NO) {
        CGFloat widthFactor = targetWidth / width;
        CGFloat heightFactor = targetHeight / height;

        if (widthFactor == 0.0) scaleFactor = heightFactor;
        else if (heightFactor == 0.0) scaleFactor = widthFactor;
        else if (widthFactor > heightFactor) scaleFactor = heightFactor;
        else scaleFactor = widthFactor;

        scaledSize = CGSizeMake(floor(width * scaleFactor), floor(height * scaleFactor));
    }

    UIGraphicsBeginImageContextWithOptions(scaledSize, NO, 1.0);
    [sourceImage drawInRect:CGRectMake(0, 0, scaledSize.width, scaledSize.height)];
    newImage = UIGraphicsGetImageFromCurrentImageContext();
    UIGraphicsEndImageContext();

    return newImage;
}

- (void)imagePickerControllerDidCancel:(UIImagePickerController *)picker {
    CDVPluginResult* pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsArray:@[]];
    [self.commandDelegate sendPluginResult:pluginResult callbackId:self.callbackId];
    [self.viewController dismissViewControllerAnimated:YES completion:nil];
}

- (void)assetsPickerController:(GMImagePickerController *)picker didFinishPickingAssets:(NSArray *)fetchArray {
    [picker dismissViewControllerAnimated:YES completion:nil];

    NSMutableArray *result_all = [[NSMutableArray alloc] init];
    CGSize targetSize = CGSizeMake(self.width, self.height);
    NSFileManager* fileMgr = [[NSFileManager alloc] init];
    NSString* docsPath = [NSTemporaryDirectory() stringByStandardizingPath];

    NSError* err = nil;
    int i = 1;
    NSString* filePath;
    CDVPluginResult* result = nil;

    for (GMFetchItem *item in fetchArray) {
        if (!item.image_fullsize) continue;

        do {
            filePath = [NSString stringWithFormat:@"%@/%@%03d.%@", docsPath, CDV_PHOTO_PREFIX, i++, @"jpg"];
        } while ([fileMgr fileExistsAtPath:filePath]);

        UIImage* image = [UIImage imageWithContentsOfFile:item.image_fullsize];
        if (!image) continue;

        NSData* data = nil;
        if (self.width == 0 && self.height == 0) {
            if (self.outputType == BASE64_STRING) {
                data = UIImageJPEGRepresentation(image, self.quality / 100.0f);
                [result_all addObject:[data base64EncodedStringWithOptions:0]];
            } else {
                if (self.quality == 100) {
                    [result_all addObject:[[NSURL fileURLWithPath:item.image_fullsize] absoluteString]];
                } else {
                    data = UIImageJPEGRepresentation(image, self.quality / 100.0f);
                    if (![data writeToFile:filePath options:NSAtomicWrite error:&err]) {
                        result = [CDVPluginResult resultWithStatus:CDVCommandStatus_IO_EXCEPTION messageAsString:[err localizedDescription]];
                        break;
                    } else {
                        [result_all addObject:[[NSURL fileURLWithPath:filePath] absoluteString]];
                    }
                }
            }
        } else {
            UIImage* scaledImage = [self imageByScalingNotCroppingForSize:image toSize:targetSize];
            data = UIImageJPEGRepresentation(scaledImage, self.quality / 100.0f);

            if (![data writeToFile:filePath options:NSAtomicWrite error:&err]) {
                result = [CDVPluginResult resultWithStatus:CDVCommandStatus_IO_EXCEPTION messageAsString:[err localizedDescription]];
                break;
            } else {
                if (self.outputType == BASE64_STRING) {
                    [result_all addObject:[data base64EncodedStringWithOptions:0]];
                } else {
                    [result_all addObject:[[NSURL fileURLWithPath:filePath] absoluteString]];
                }
            }
        }
    }

    if (result == nil) {
        result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsArray:result_all];
    }

    [self.commandDelegate sendPluginResult:result callbackId:self.callbackId];
}

- (void)assetsPickerControllerDidCancel:(GMImagePickerController *)picker {
    CDVPluginResult* pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsArray:@[]];
    [self.commandDelegate sendPluginResult:pluginResult callbackId:self.callbackId];
    [picker dismissViewControllerAnimated:YES completion:nil];
}

@end