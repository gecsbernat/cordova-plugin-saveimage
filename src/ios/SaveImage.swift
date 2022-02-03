import Foundation
import Photos
import WebKit

@objc(SaveImage) class SaveImage : CDVPlugin {
    
    lazy var concurrentQueue: DispatchQueue = DispatchQueue(label: "photo-library.queue.plugin", qos: DispatchQoS.utility, attributes: [.concurrent])
    
    override func onMemoryWarning() {
        NSLog("-- MEMORY WARNING --")
    }
    
    @objc func requestAuthorization(_ command: CDVInvokedUrlCommand) {
        let service = ImageService.instance
        service.requestAuthorization({
            let pluginResult = CDVPluginResult(status: CDVCommandStatus_OK)
            self.commandDelegate!.send(pluginResult, callbackId: command.callbackId    )
        }, failure: { (err) in
            let pluginResult = CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: err)
            self.commandDelegate!.send(pluginResult, callbackId: command.callbackId    )
        })
    }
    
    @objc func saveImage(_ command: CDVInvokedUrlCommand) {
        concurrentQueue.async {
            if PHPhotoLibrary.authorizationStatus() != .authorized {
                let pluginResult = CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: ImageService.PERMISSION_ERROR)
                self.commandDelegate!.send(pluginResult, callbackId: command.callbackId)
                return
            }
            let service = ImageService.instance
            let url = command.arguments[1] as! String
            let album = command.arguments[2] as! String
            NSLog("album: %@, %@", album, url);
            service.saveImage(url, album: album) { (libraryItem: NSDictionary?, error: String?) in
                if (error != nil) {
                    let pluginResult = CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: error)
                    self.commandDelegate!.send(pluginResult, callbackId: command.callbackId)
                } else {
                    let pluginResult = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: libraryItem as! [String: AnyObject]?)
                    self.commandDelegate!.send(pluginResult, callbackId: command.callbackId    )
                }
            }
        }
    }
    
    @objc func saveVideo(_ command: CDVInvokedUrlCommand) {
        concurrentQueue.async {
            if PHPhotoLibrary.authorizationStatus() != .authorized {
                let pluginResult = CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: ImageService.PERMISSION_ERROR)
                self.commandDelegate!.send(pluginResult, callbackId: command.callbackId)
                return
            }
            let service = ImageService.instance
            let url = command.arguments[0] as! String
            let album = command.arguments[1] as! String
            service.saveVideo(url, album: album) { (_ libraryItem: NSDictionary?, error: String?) in
                if (error != nil) {
                    let pluginResult = CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: error)
                    self.commandDelegate!.send(pluginResult, callbackId: command.callbackId)
                } else {
                    let pluginResult = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: libraryItem as! [String: AnyObject]?)
                    self.commandDelegate!.send(pluginResult, callbackId: command.callbackId    )
                }
            }
        }
    }
    
}
