var exec = require('cordova/exec');
module.exports = {
	requestAuthorization: function (options, success, error) {
		exec(success, error, "SaveImage", "requestAuthorization", [options]);
	},
	saveImage: function (fileName, image, album, success, error) {
		exec(success, error, "SaveImage", "saveImage", [fileName, image, album]);
	}
};