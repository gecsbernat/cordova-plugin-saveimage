# cordova-plugin-saveimage
This plugin helps you save images on iOS/Android

### Installation
```sh
$ ionic cordova plugin add https://github.com/gecsbernat/cordova-plugin-saveimage.git
```

### Usage
saveimage.service.ts
```typescript
import { Injectable } from "@angular/core";

declare const SaveImage: any;

@Injectable({ providedIn: 'root' })
export class SaveImageService {
    constructor() { }

    saveImage(name: string, url: string, album: string): Promise<void> {
        return new Promise((resolve, reject) => {
            SaveImage.requestAuthorization({ read: false, write: true }, () => {
                SaveImage.saveImage(name, url, album, () => {
                    resolve();
                }, (error: any) => {
                    reject(error);
                });
            }, (error: any) => {
                reject(error);
            });
        });
    }
}
```