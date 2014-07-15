/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/* $Id$ */

package org.apache.fop.render.pdf;
import java.io.DataInput;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.apache.commons.io.IOUtils;

import org.apache.xmlgraphics.image.loader.impl.ImageRawJPEG;
import org.apache.xmlgraphics.image.loader.impl.JPEGConstants;
import org.apache.xmlgraphics.image.loader.impl.JPEGFile;
import org.apache.xmlgraphics.image.loader.util.ImageUtil;

import org.apache.fop.pdf.DCTFilter;
import org.apache.fop.pdf.PDFDeviceColorSpace;
import org.apache.fop.pdf.PDFDocument;
import org.apache.fop.pdf.PDFFilter;
import org.apache.fop.pdf.PDFFilterList;

/**
 * PDFImage implementation for the PDF renderer which handles raw JPEG images.
 * <p>
 * The JPEG is copied to the XObject's stream as-is but some elements (marker segments) are
 * filtered. For example, an embedded color profile is filtered since it is already added as
 * a PDF object and associated with the XObject. This way, the PDF file size is kept as small
 * as possible.
 */
public class ImageRawJPEGAdapter extends AbstractImageAdapter {

    private PDFFilter pdfFilter;

    /**
     * Creates a new PDFImage from an Image instance.
     * @param image the JPEG image
     * @param key XObject key
     */
    public ImageRawJPEGAdapter(ImageRawJPEG image, String key) {
        super(image, key);
    }

    /**
     * Returns the {@link ImageRawJPEG} instance for this adapter.
     * @return the image instance
     */
    public ImageRawJPEG getImage() {
        return ((ImageRawJPEG)this.image);
    }

    /** {@inheritDoc} */
    public void setup(PDFDocument doc) {
        pdfFilter = new DCTFilter();
        pdfFilter.setApplied(true);

        super.setup(doc);
    }

    /** {@inheritDoc} */
    public PDFDeviceColorSpace getColorSpace() {
        // DeviceGray, DeviceRGB, or DeviceCMYK
        return toPDFColorSpace(getImageColorSpace());
    }

    /** {@inheritDoc} */
    public int getBitsPerComponent() {
        return 8;
    }

    /** @return true for CMYK images generated by Adobe Photoshop */
    public boolean isInverted() {
        return getImage().isInverted();
    }

    /** {@inheritDoc} */
    public PDFFilter getPDFFilter() {
        return pdfFilter;
    }

    /** {@inheritDoc} */
    public void outputContents(OutputStream out) throws IOException {
        InputStream in = getImage().createInputStream();
        in = ImageUtil.decorateMarkSupported(in);
        try {
            JPEGFile jpeg = new JPEGFile(in);
            DataInput din = jpeg.getDataInput();

            //Copy the whole JPEG file except:
            // - the ICC profile
            //TODO Thumbnails could safely be skipped, too.
            //TODO Metadata (XMP, IPTC, EXIF) could safely be skipped, too.
            while (true) {
                int reclen;
                int segID = jpeg.readMarkerSegment();
                switch (segID) {
                case JPEGConstants.SOI:
                    out.write(0xFF);
                    out.write(segID);
                    break;
                case JPEGConstants.EOI:
                case JPEGConstants.SOS:
                    out.write(0xFF);
                    out.write(segID);
                    IOUtils.copy(in, out); //Just copy the rest!
                    return;
                /*
                case JPEGConstants.APP1: //Metadata
                case JPEGConstants.APPD:
                    jpeg.skipCurrentMarkerSegment();
                    break;*/
                case JPEGConstants.APP2: //ICC (see ICC1V42.pdf)
                    boolean skipICCProfile = false;
                    in.mark(16);
                    try {
                        reclen = jpeg.readSegmentLength();
                        // Check for ICC profile
                        byte[] iccString = new byte[11];
                        din.readFully(iccString);
                        din.skipBytes(1); //string terminator (null byte)

                        if ("ICC_PROFILE".equals(new String(iccString, "US-ASCII"))) {
                            skipICCProfile = (this.image.getICCProfile() != null);
                        }
                    } finally {
                        in.reset();
                    }
                    if (skipICCProfile) {
                        //ICC profile is skipped as it is already embedded as a PDF object
                        jpeg.skipCurrentMarkerSegment();
                        break;
                    }
                default:
                    out.write(0xFF);
                    out.write(segID);

                    reclen = jpeg.readSegmentLength();
                    //write short
                    out.write((reclen >>> 8) & 0xFF);
                    out.write((reclen >>> 0) & 0xFF);
                    int left = reclen - 2;
                    byte[] buf = new byte[2048];
                    while (left > 0) {
                        int part = Math.min(buf.length, left);
                        din.readFully(buf, 0, part);
                        out.write(buf, 0, part);
                        left -= part;
                    }
                }
            }
        } finally {
            IOUtils.closeQuietly(in);
        }
    }

    /** {@inheritDoc} */
    public String getFilterHint() {
        return PDFFilterList.JPEG_FILTER;
    }

}

