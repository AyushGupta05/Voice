// Create a synthetic image, check a pixel, and write a marker file.
newImage("Fidelis smoke", "8-bit black", 4, 4, 1);
setPixel(1, 1, 255);
if (getPixel(1, 1) != 255)
    exit("Synthetic image check failed");
File.saveString("ok", getArgument());
close();
