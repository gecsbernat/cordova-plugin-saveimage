import Photos
import Foundation
import AssetsLibrary
import MobileCoreServices

extension PHAsset {
    var originalFileName: String? {
        var result: String?
        if #available(iOS 9.0, *) {
            let resources = PHAssetResource.assetResources(for: self)
            if let resource = resources.first {
                result = resource.originalFilename
            }
        }
        return result
    }
    var fileName: String? {
        return self.value(forKey: "filename") as? String
    }
}

final class ImageService {
    
    let fetchOptions: PHFetchOptions!
    let dateFormatter: DateFormatter!
    static let PERMISSION_ERROR = "Permission Denial: This application is not allowed to access Photo data."
    let dataURLPattern = try! NSRegularExpression(pattern: "^data:.+?;base64,", options: NSRegularExpression.Options(rawValue: 0))
    let assetCollectionTypes = [PHAssetCollectionType.album, PHAssetCollectionType.smartAlbum]
    
    fileprivate init() {
        fetchOptions = PHFetchOptions()
        fetchOptions.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
        if #available(iOS 9.0, *) {
            fetchOptions.includeAssetSourceTypes = [.typeUserLibrary, .typeiTunesSynced, .typeCloudShared]
        }
        dateFormatter = DateFormatter()
        dateFormatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ss.SSSZZZZZ"
    }
    
    class var instance: ImageService {
        struct SingletonWrapper {
            static let singleton = ImageService()
        }
        return SingletonWrapper.singleton
    }
    
    func requestAuthorization(_ success: @escaping () -> Void, failure: @escaping (_ err: String) -> Void ) {
        let status = PHPhotoLibrary.authorizationStatus()
        if status == .authorized {
            success()
            return
        }
        if status == .notDetermined {
            PHPhotoLibrary.requestAuthorization() { (status) -> Void in
                switch status {
                case .authorized:
                    success()
                default:
                    failure("requestAuthorization denied by user")
                }
            }
            return
        }
        let settingsUrl = URL(string: UIApplicationOpenSettingsURLString)
        if let url = settingsUrl {
            UIApplication.shared.openURL(url)
        } else {
            failure("could not open settings url")
        }
    }
    
    func saveImage(_ url: String, album: String, completion: @escaping (_ libraryItem: NSDictionary?, _ error: String?)->Void) {
        let sourceData: Data
        do {
            sourceData = try getDataFromURL(url)
        } catch {
            completion(nil, "\(error)")
            return
        }
        let assetsLibrary = ALAssetsLibrary()
        func saveImage(_ photoAlbum: PHAssetCollection) {
            assetsLibrary.writeImageData(toSavedPhotosAlbum: sourceData, metadata: nil) { (assetUrl: URL?, error: Error?) in
                if error != nil {
                    completion(nil, "Could not write image to album: \(error)")
                    return
                }
                guard let assetUrl = assetUrl else {
                    completion(nil, "Writing image to album resulted empty asset")
                    return
                }
                self.putMediaToAlbum(assetsLibrary, url: assetUrl, album: album, completion: { (error) in
                    if error != nil {
                        completion(nil, error)
                    } else {
                        let fetchResult = PHAsset.fetchAssets(withALAssetURLs: [assetUrl], options: nil)
                        var libraryItem: NSDictionary? = nil
                        if fetchResult.count == 1 {
                            let asset = fetchResult.firstObject
                            if let asset = asset {
                                libraryItem = self.assetToLibraryItem(asset: asset, useOriginalFileNames: false, includeAlbumData: true)
                            }
                        }
                        completion(libraryItem, nil)
                    }
                })
            }
        }
        if let photoAlbum = ImageService.getPhotoAlbum(album) {
            saveImage(photoAlbum)
            return
        }
        ImageService.createPhotoAlbum(album) { (photoAlbum: PHAssetCollection?, error: String?) in
            guard let photoAlbum = photoAlbum else {
                completion(nil, error)
                return
            }
            saveImage(photoAlbum)
        }
    }
    
    func saveVideo(_ url: String, album: String, completion: @escaping (_ libraryItem: NSDictionary?, _ error: String?)->Void) {
        guard let videoURL = URL(string: url) else {
            completion(nil, "Could not parse DataURL")
            return
        }
        let assetsLibrary = ALAssetsLibrary()
        func saveVideo(_ photoAlbum: PHAssetCollection) {
            if !assetsLibrary.videoAtPathIs(compatibleWithSavedPhotosAlbum: videoURL) {
                completion(nil, "Provided video is not compatible with Saved Photo album")
                return
            }
            assetsLibrary.writeVideoAtPath(toSavedPhotosAlbum: videoURL) { (assetUrl: URL?, error: Error?) in
                if error != nil {
                    completion(nil, "Could not write video to album: \(error)")
                    return
                }
                guard let assetUrl = assetUrl else {
                    completion(nil, "Writing video to album resulted empty asset")
                    return
                }
                self.putMediaToAlbum(assetsLibrary, url: assetUrl, album: album, completion: { (error) in
                    if error != nil {
                        completion(nil, error)
                    } else {
                        let fetchResult = PHAsset.fetchAssets(withALAssetURLs: [assetUrl], options: nil)
                        var libraryItem: NSDictionary? = nil
                        if fetchResult.count == 1 {
                            let asset = fetchResult.firstObject
                            if let asset = asset {
                                libraryItem = self.assetToLibraryItem(asset: asset, useOriginalFileNames: false, includeAlbumData: true)
                            }
                        }
                        completion(libraryItem, nil)
                    }
                })
            }
        }
        if let photoAlbum = ImageService.getPhotoAlbum(album) {
            saveVideo(photoAlbum)
            return
        }
        ImageService.createPhotoAlbum(album) { (photoAlbum: PHAssetCollection?, error: String?) in
            guard let photoAlbum = photoAlbum else {
                completion(nil, error)
                return
            }
            saveVideo(photoAlbum)
        }
    }
    
    private func assetToLibraryItem(asset: PHAsset, useOriginalFileNames: Bool, includeAlbumData: Bool) -> NSMutableDictionary {
        let libraryItem = NSMutableDictionary()
        libraryItem["id"] = asset.localIdentifier
        libraryItem["fileName"] = useOriginalFileNames ? asset.originalFileName : asset.fileName
        libraryItem["width"] = asset.pixelWidth
        libraryItem["height"] = asset.pixelHeight
        let fname = libraryItem["fileName"] as! String
        libraryItem["mimeType"] = self.mimeTypeForPath(path: fname)
        libraryItem["creationDate"] = self.dateFormatter.string(from: asset.creationDate!)
        if let location = asset.location {
            libraryItem["latitude"] = location.coordinate.latitude
            libraryItem["longitude"] = location.coordinate.longitude
        }
        if includeAlbumData {
            var assetCollectionIds = [String]()
            for assetCollectionType in self.assetCollectionTypes {
                let albumsOfAsset = PHAssetCollection.fetchAssetCollectionsContaining(asset, with: assetCollectionType, options: nil)
                albumsOfAsset.enumerateObjects({ (assetCollection: PHAssetCollection, index, stop) in
                    assetCollectionIds.append(assetCollection.localIdentifier)
                })
            }
            libraryItem["albumIds"] = assetCollectionIds
        }
        return libraryItem
    }
    
    func mimeTypeForPath(path: String) -> String {
        let url = NSURL(fileURLWithPath: path)
        let pathExtension = url.pathExtension
        if let uti = UTTypeCreatePreferredIdentifierForTag(kUTTagClassFilenameExtension, pathExtension! as NSString, nil)?.takeRetainedValue() {
            if let mimetype = UTTypeCopyPreferredTagWithClass(uti, kUTTagClassMIMEType)?.takeRetainedValue() {
                return mimetype as String
            }
        }
        return "application/octet-stream"
    }
    
    enum PhotoLibraryError: Error, CustomStringConvertible {
        case error(description: String)
        var description: String {
            switch self {
            case .error(let description): return description
            }
        }
    }
    
    fileprivate func getDataFromURL(_ url: String) throws -> Data {
        if url.hasPrefix("data:") {
            guard let match = self.dataURLPattern.firstMatch(in: url, options: NSRegularExpression.MatchingOptions(rawValue: 0), range: NSMakeRange(0, url.count)) else {
                throw PhotoLibraryError.error(description: "The dataURL could not be parsed")
            }
            let dataPos = match.range(at: 0).length
            let base64 = (url as NSString).substring(from: dataPos)
            guard let decoded = Data(base64Encoded: base64, options: NSData.Base64DecodingOptions(rawValue: 0)) else {
                throw PhotoLibraryError.error(description: "The dataURL could not be decoded")
            }
            return decoded
        } else {
            guard let nsURL = URL(string: url) else {
                throw PhotoLibraryError.error(description: "The url could not be decoded: \(url)")
            }
            guard let fileContent = try? Data(contentsOf: nsURL) else {
                throw PhotoLibraryError.error(description: "The url could not be read: \(url)")
            }
            return fileContent
        }
    }
    
    fileprivate func putMediaToAlbum(_ assetsLibrary: ALAssetsLibrary, url: URL, album: String, completion: @escaping (_ error: String?)->Void) {
        assetsLibrary.asset(for: url, resultBlock: { (asset: ALAsset?) in
            guard let asset = asset else {
                completion("Retrieved asset is nil")
                return
            }
            ImageService.getAlPhotoAlbum(assetsLibrary, album: album, completion: { (alPhotoAlbum: ALAssetsGroup?, error: String?) in
                if error != nil {
                    completion("getting photo album caused error: \(error)")
                    return
                }
                alPhotoAlbum!.add(asset)
                completion(nil)
            })
        }, failureBlock: { (error: Error?) in
            completion("Could not retrieve saved asset: \(error)")
        })
    }
    
    
    fileprivate static func getPhotoAlbum(_ album: String) -> PHAssetCollection? {
        let fetchOptions = PHFetchOptions()
        fetchOptions.predicate = NSPredicate(format: "title = %@", album)
        let fetchResult = PHAssetCollection.fetchAssetCollections(with: .album, subtype: .albumRegular, options: fetchOptions)
        guard let photoAlbum = fetchResult.firstObject else {
            return nil
        }
        return photoAlbum
    }
    
    fileprivate static func createPhotoAlbum(_ album: String, completion: @escaping (_ photoAlbum: PHAssetCollection?, _ error: String?)->()) {
        var albumPlaceholder: PHObjectPlaceholder?
        PHPhotoLibrary.shared().performChanges({
            let createAlbumRequest = PHAssetCollectionChangeRequest.creationRequestForAssetCollection(withTitle: album)
            albumPlaceholder = createAlbumRequest.placeholderForCreatedAssetCollection
        }) { success, error in
            guard let placeholder = albumPlaceholder else {
                completion(nil, "Album placeholder is nil")
                return
            }
            let fetchResult = PHAssetCollection.fetchAssetCollections(withLocalIdentifiers: [placeholder.localIdentifier], options: nil)
            guard let photoAlbum = fetchResult.firstObject else {
                completion(nil, "FetchResult has no PHAssetCollection")
                return
            }
            if success {
                completion(photoAlbum, nil)
            }
            else {
                completion(nil, "\(String(describing: error))")
            }
        }
    }
    
    fileprivate static func getAlPhotoAlbum(_ assetsLibrary: ALAssetsLibrary, album: String, completion: @escaping (_ alPhotoAlbum: ALAssetsGroup?, _ error: String?)->Void) {
        var groupPlaceHolder: ALAssetsGroup?
        assetsLibrary.enumerateGroupsWithTypes(ALAssetsGroupAlbum, usingBlock: { (group: ALAssetsGroup?, _ ) in
            guard let group = group else {
                guard let groupPlaceHolder = groupPlaceHolder else {
                    completion(nil, "Could not find album")
                    return
                }
                completion(groupPlaceHolder, nil)
                return
            }
            if group.value(forProperty: ALAssetsGroupPropertyName) as? String == album {
                groupPlaceHolder = group
            }
        }, failureBlock: { (error: Error?) in
            completion(nil, "Could not enumerate assets library")
        })
    }
}
