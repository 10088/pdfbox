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
package org.apache.pdfbox.rendering;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.TexturePaint;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.GeneralPath;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType0;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType2;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.rendering.font.CIDType0Glyph2D;
import org.apache.pdfbox.rendering.font.Glyph2D;
import org.apache.pdfbox.rendering.font.TTFGlyph2D;
import org.apache.pdfbox.rendering.font.Type1Glyph2D;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1CFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.graphics.PDLineDashPattern;
import org.apache.pdfbox.pdmodel.graphics.blend.BlendMode;
import org.apache.pdfbox.pdmodel.graphics.state.PDSoftMask;
import org.apache.pdfbox.pdmodel.graphics.blend.SoftMaskPaint;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDTilingPattern;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShading;
import org.apache.pdfbox.pdmodel.graphics.state.PDGraphicsState;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.PDFGraphicsStreamEngine;
import org.apache.pdfbox.util.Vector;

/**
 * Paints a page in a PDF document to a Graphics context.
 * 
 * @author Ben Litchfield
 */
public class PageDrawer extends PDFGraphicsStreamEngine
{
    private static final Log LOG = LogFactory.getLog(PageDrawer.class);

    // parent document renderer
    private final PDFRenderer renderer;

    private Graphics2D graphics;

    // clipping winding rule used for the clipping path
    private int clipWindingRule = -1;
    private GeneralPath linePath = new GeneralPath();

    // last clipping path
    private Area lastClip;

    // buffered clipping area for text being drawn
    private Area textClippingArea;

    private final Map<PDFont, Glyph2D> fontGlyph2D = new HashMap<PDFont, Glyph2D>();

    private PDRectangle pageSize;
    
    /**
     * Default constructor, loads properties from file.
     * 
     * @throws IOException If there is an error loading properties from the file.
     */
    public PageDrawer(PDFRenderer renderer, PDPage page) throws IOException
    {
        super(page);
        this.renderer = renderer;
    }

    /**
     * Tiling pattern constructor, loads properties from file.
     *
     * @throws IOException If there is an error loading properties from the file.
     */
    PageDrawer(PDFRenderer renderer) throws IOException
    {
        super(null);
        this.renderer = renderer;
    }

    /**
     * Returns the parent renderer.
     * @return the parent renderer.
     */
    public PDFRenderer getRenderer()
    {
        return renderer;
    }

    /**
     * This will draw the page to the requested context.
     * 
     * @param g The graphics context to draw onto.
     * @param pageSize The size of the page to draw.
     * 
     * @throws IOException If there is an IO error while drawing the page.
     */
    public void drawPage(Graphics g, PDRectangle pageSize) throws IOException
    {
        graphics = (Graphics2D) g;
        this.pageSize = pageSize;
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        graphics.translate(0, (int) pageSize.getHeight());
        graphics.scale(1, -1);
        // TODO use getStroke() to set the initial stroke
        graphics.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));

        // Only if there is some content, we have to process it.
        // Otherwise we are done here and we will produce an empty page
        PDPage page = getPage();
        if (page.getContents() != null)
        {
            PDResources resources = page.findResources();
            processStream(resources, page.getContents().getStream(), page.findCropBox());
        }
        else
        {
            initStream(page.findCropBox());
        }

        for (PDAnnotation annotation : page.getAnnotations())
        {
            PDRectangle rect = annotation.getRectangle();
            String appearanceName = annotation.getAppearanceStream();
            PDAppearanceDictionary appearDictionary = annotation.getAppearance();
            if (appearDictionary != null)
            {
                if (appearanceName == null)
                {
                    appearanceName = "default";
                }
                Map<String, PDAppearanceStream> appearanceMap = appearDictionary.getNormalAppearance();
                if (appearanceMap != null)
                {
                    PDAppearanceStream appearance = appearanceMap.get(appearanceName);
                    if (appearance != null)
                    {
                        saveGraphicsState();

                        PDRectangle bBox = appearance.getBoundingBox();
                        
                        Rectangle2D rect2D = new Rectangle2D.Float(
                                rect.getLowerLeftX(), 
                                rect.getLowerLeftY(), 
                                rect.getWidth(), 
                                rect.getHeight());
                        Matrix matrix = appearance.getMatrix();
                        if (matrix == null)
                        {
                            matrix = new Matrix();
                        }
                        
                        // PDF Spec 12.5.5:
                        // a) The appearance's bounding box (specified by its BBox entry) 
                        // shall be transformed, using Matrix, to produce a quadrilateral 
                        // with arbitrary orientation.
                        Point2D p1 = new Point2D.Float(bBox.getLowerLeftX(), bBox.getLowerLeftY());
                        Point2D p2 = new Point2D.Float(bBox.getUpperRightX(), bBox.getUpperRightY());
                        matrix.createAffineTransform().transform(p1, p1);
                        matrix.createAffineTransform().transform(p2, p2);
                        Rectangle2D transformedBBox = new Rectangle2D.Float(
                                (float) Math.min(p1.getX(), p2.getX()),
                                (float) Math.min(p1.getY(), p2.getY()),
                                (float) Math.abs(p2.getX() - p1.getX()),
                                (float) Math.abs(p2.getY() - p1.getY()));

                        // PDF Spec 12.5.5:
                        // b) A matrix A shall be computed that scales and translates 
                        // the transformed appearance box to align with the edges
                        // of the annotation's rectangle
                        //
                        // code inspired from
                        // http://stackoverflow.com/a/14015713/535646
                        AffineTransform at = new AffineTransform();
                        at.translate(rect2D.getMinX(), rect2D.getMinY());
                        at.scale(rect2D.getWidth() / transformedBBox.getWidth(), rect2D.getHeight() / transformedBBox.getHeight());
                        at.translate(-transformedBBox.getMinX(), -transformedBBox.getMinY());
                        Matrix matrixA = new Matrix();
                        matrixA.setFromAffineTransform(at);
                        
                        // PDF Spec 12.5.5:
                        // c) Matrix shall be concatenated with A to form a matrix AA 
                        // that maps from the appearance's coordinate system to 
                        // the annotation's rectangle in default user space
                        Matrix matrixAA = matrix.multiply(matrixA);
                        
                        Point2D point = new Point2D.Float(matrixAA.getXPosition(), matrixAA.getYPosition());
                        matrixAA.setValue(2, 0, 0);
                        matrixAA.setValue(2, 1, 0);
                        
                        getGraphicsState().setCurrentTransformationMatrix(matrixAA);

                        // Calculate clipping
                        // PDF Spec 12.5.5:
                        // a self-contained content stream that shall be rendered 
                        // inside the annotation rectangle
                        Rectangle2D clipRect2D = new Rectangle2D.Float(
                                (float) (rect2D.getMinX()-point.getX()),
                                (float) (rect2D.getMinY()-point.getY()),
                                (float) rect2D.getWidth(),
                                (float) rect2D.getHeight());
                        getGraphicsState().intersectClippingPath(new Area(clipRect2D));

                        graphics.translate((int) point.getX(), (int) point.getY());
                        lastClip = null;
                        processSubStream(appearance.getResources(), appearance.getStream());
                        graphics.translate(-(int) point.getX(), -(int) point.getY());
                       
                        restoreGraphicsState();
                    }
                }
            }
        }
        graphics = null;
    }

    /**
     * This will draw the pattern stream to the requested context.
     *
     * @param g The graphics context to draw onto.
     * @param pattern The tiling pattern to be used.
     * @param pageDimension The size of the page to draw.
     * @throws IOException If there is an IO error while drawing the page.
     */
    public void drawTilingPattern(Graphics2D g, PDTilingPattern pattern, PDRectangle pageDimension,
                                  Matrix matrix, PDColorSpace colorSpace, PDColor color)
                                  throws IOException
    {
        pageSize = pageDimension;
        graphics = g;
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);

        initStream(pageDimension);

        // transformPoint ctm
        Matrix concat = matrix.multiply(getGraphicsState().getCurrentTransformationMatrix());
        getGraphicsState().setCurrentTransformationMatrix(concat);

        // color
        if (colorSpace != null)
        {
            getGraphicsState().setNonStrokingColorSpace(colorSpace);
            getGraphicsState().setNonStrokingColor(color);
            getGraphicsState().setStrokingColorSpace(colorSpace);
            getGraphicsState().setStrokingColor(color);
        }

        processSubStream(pattern.getResources(), (COSStream)pattern.getCOSObject());
    }

    // sets the clipping path using caching for performance, we track lastClip manually because
    // Graphics2D#getClip() returns a new object instead of the same one passed to setClip
    private void setClip()
    {
        Area clippingPath = getGraphicsState().getCurrentClippingPath();
        if (clippingPath != lastClip)
        {
            graphics.setClip(clippingPath);
            lastClip = clippingPath;
        }
    }

    @Override
    public void beginText() throws IOException
    {
        setClip();
    }

    @Override
    protected void showText(byte[] string, float adjustment) throws IOException
    {
        PDGraphicsState state = getGraphicsState();
        RenderingMode renderingMode = state.getTextState().getRenderingMode();

        // buffer the text clip because it represents a single clipping area
        if (renderingMode.isClip())
        {
            textClippingArea = new Area();
        }

        super.showText(string, adjustment);

        // apply the buffered clip as one area
        if (renderingMode.isClip())
        {
            state.intersectClippingPath(textClippingArea);
            textClippingArea = null;
        }
    }

    @Override
    protected void showGlyph(Matrix textRenderingMatrix, PDFont font, int code, String unicode,
                             Vector displacement) throws IOException
    {
        AffineTransform at = textRenderingMatrix.createAffineTransform();
        at.concatenate(font.getFontMatrix().createAffineTransform());

        if (font instanceof PDType3Font)
        {
            // Type3 fonts use PDF streams for each character
            drawType3String((PDType3Font) font, code, at);
        }
        else
        {
            // all other fonts use vectors
            Glyph2D glyph2D = createGlyph2D(font);
            drawGlyph2D(glyph2D, code, at);
        }
    }

    /**
     * Render the font using the Glyph2D interface.
     * 
     * @param glyph2D the Glyph2D implementation provided a GeneralPath for each glyph
     * @param code character code
     * @param at the transformation
     * @throws IOException if something went wrong
     */
    private void drawGlyph2D(Glyph2D glyph2D, int code, AffineTransform at) throws IOException
    {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        PDGraphicsState state = getGraphicsState();
        RenderingMode renderingMode = state.getTextState().getRenderingMode();

        GeneralPath path = glyph2D.getPathForCharacterCode(code);
        if (path != null)
        {
            Shape glyph = at.createTransformedShape(path);

            if (renderingMode.isFill())
            {
                graphics.setComposite(state.getNonStrokingJavaComposite());
                graphics.setPaint(getNonStrokingPaint());
                graphics.fill(glyph);
            }

            if (renderingMode.isStroke())
            {
                graphics.setComposite(state.getStrokingJavaComposite());
                graphics.setPaint(getStrokingPaint());
                graphics.setStroke(getStroke());
                graphics.draw(glyph);
            }

            if (renderingMode.isClip())
            {
                textClippingArea.add(new Area(glyph));
            }
        }
    }

    /**
     * Render the text using a type 3 font.
     * 
     * @param font the type3 font
     * @param code internal PDF character codes of glyph
     * @param at the transformation
     * 
     * @throws IOException if something went wrong
     */
    private void drawType3String(PDType3Font font, int code, AffineTransform at) throws IOException
    {
        COSStream stream = font.getCharStream(code);
        if (stream != null)
        {
            // save the current graphics state and matrices
            saveGraphicsState();
            Matrix textMatrix = getTextMatrix();
            Matrix textLineMatrix = getTextLineMatrix();

            Matrix ctm = new Matrix();
            ctm.setFromAffineTransform(at);
            getGraphicsState().setCurrentTransformationMatrix(ctm);
            processSubStream(font.getType3Resources(), stream);

            // restore the saved graphics state and matrices
            restoreGraphicsState();
            setTextLineMatrix(textLineMatrix);
            setTextMatrix(textMatrix);

        }
        else
        {
            LOG.error("Stream for Type 3 character " + code + " not found");
        }
    }

    /**
     * Provide a Glyph2D for the given font.
     * 
     * @param font the font
     * @return the implementation of the Glyph2D interface for the given font
     * @throws IOException if something went wrong
     */
    private Glyph2D createGlyph2D(PDFont font) throws IOException
    {
        // Is there already a Glyph2D for the given font?
        if (fontGlyph2D.containsKey(font))
        {
            return fontGlyph2D.get(font);
        }

        Glyph2D glyph2D = null;
        if (font instanceof PDTrueTypeFont)
        {
            PDTrueTypeFont ttfFont = (PDTrueTypeFont)font;
            glyph2D = new TTFGlyph2D(ttfFont);  // TTF is never null
        }
        else if (font instanceof PDType1Font)
        {
            PDType1Font pdType1Font = (PDType1Font)font;
            glyph2D = new Type1Glyph2D(pdType1Font); // T1 is never null
        }
        else if (font instanceof PDType1CFont)
        {
            PDType1CFont type1CFont = (PDType1CFont)font;
            if (type1CFont.getCFFType1Font() != null) // todo: could be null (need to incorporate fallback)
            {
                glyph2D = new Type1Glyph2D(type1CFont);
            }
        }
        else if (font instanceof PDType0Font)
        {
            PDType0Font type0Font = (PDType0Font) font;
            if (type0Font.getDescendantFont() instanceof PDCIDFontType2)
            {
                glyph2D = new TTFGlyph2D(type0Font); // TTF is never null
            }
            else if (type0Font.getDescendantFont() instanceof PDCIDFontType0)
            {
                // a Type0 CIDFont contains CFF font
                PDCIDFontType0 cidType0Font = (PDCIDFontType0)type0Font.getDescendantFont();
                glyph2D = new CIDType0Glyph2D(cidType0Font); // todo: could be null (need incorporate fallback)
            }
        }
        else
        {
            throw new IllegalStateException("Bad font type: " + font.getClass().getSimpleName());
        }

        // cache the Glyph2D instance
        if (glyph2D != null)
        {
            fontGlyph2D.put(font, glyph2D);
        }

        if (glyph2D == null)
        {
            // todo: make sure this never happens
            throw new UnsupportedOperationException("No font for " + font.getBaseFont());
        }

        return glyph2D;
    }

    @Override
    public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3)
    {
        // to ensure that the path is created in the right direction, we have to create
        // it by combining single lines instead of creating a simple rectangle
        linePath.moveTo((float) p0.getX(), (float) p0.getY());
        linePath.lineTo((float) p1.getX(), (float) p1.getY());
        linePath.lineTo((float) p2.getX(), (float) p2.getY());
        linePath.lineTo((float) p3.getX(), (float) p3.getY());

        // close the subpath instead of adding the last line so that a possible set line
        // cap style isn't taken into account at the "beginning" of the rectangle
        linePath.closePath();
    }

    /**
     * Generates AWT raster for a soft mask
     * 
     * @param softMask
     * @return AWT raster for soft mask
     * @throws IOException
     */
    private Raster createSoftMaskRaster(PDSoftMask softMask) throws IOException
    {
        TransparencyGroup transparencyGroup = createTransparencyGroup(softMask.getGroup());
        COSName subtype = softMask.getSubType();
        if (COSName.ALPHA.equals(subtype))
        {
            return transparencyGroup.getAlphaRaster();
        }
        else if (COSName.LUMINOSITY.equals(subtype))
        {
            return transparencyGroup.getLuminosityRaster();
        }
        else
        {
            throw new IOException("Invalid soft mask subtype.");
        }
    }

    private Paint applySoftMaskToPaint(Paint parentPaint, PDSoftMask softMask) throws IOException  
    {
        if (softMask != null) 
        {
            return new SoftMaskPaint(parentPaint, createSoftMaskRaster(softMask));
        }
        else 
        {
            return parentPaint;
        }
    }

    // returns the stroking AWT Paint
    private Paint getStrokingPaint() throws IOException
    {
        PDGraphicsState graphicsState = getGraphicsState();
        return applySoftMaskToPaint(graphicsState.getStrokingColorSpace()
                .toPaint(renderer, graphicsState.getStrokingColor(), (int)pageSize.getHeight()),
                         graphicsState.getSoftMask());
    }

    // returns the non-stroking AWT Paint
    private Paint getNonStrokingPaint() throws IOException
    {
        return getGraphicsState().getNonStrokingColorSpace()
                .toPaint(renderer, getGraphicsState().getNonStrokingColor(), (int)pageSize.getHeight());
    }

    // create a new stroke based on the current CTM and the current stroke
    private BasicStroke getStroke()
    {
        PDGraphicsState state = getGraphicsState();

        // apply the CTM
        float lineWidth = transformWidth(state.getLineWidth());

        // minimum line width as used by Adobe Reader
        if (lineWidth < 0.25)
        {
            lineWidth = 0.25f;
        }

        PDLineDashPattern dashPattern = state.getLineDashPattern();
        int phaseStart = dashPattern.getPhase();
        float[] dashArray = dashPattern.getDashArray();
        if (dashArray != null)
        {
            // apply the CTM
            for (int i = 0; i < dashArray.length; ++i)
            {
                dashArray[i] = transformWidth(dashArray[i]);
            }
            phaseStart = (int)transformWidth(phaseStart);

            // empty dash array is illegal
            if (dashArray.length == 0)
            {
                dashArray = null;
            }
        }
        return new BasicStroke(lineWidth, state.getLineCap(), state.getLineJoin(),
                               state.getMiterLimit(), dashArray, phaseStart);
    }

    @Override
    public void strokePath() throws IOException
    {
        graphics.setComposite(getGraphicsState().getStrokingJavaComposite());
        Paint strokingPaint = getStrokingPaint();
        if (strokingPaint == null)
        {
            LOG.info("ColorSpace " + getGraphicsState().getStrokingColorSpace().getName() +
                     " doesn't provide a stroking color, using white instead!");
            strokingPaint = Color.WHITE;// ((PageDrawer)context).strokePath();
        }
        graphics.setPaint(strokingPaint);
        graphics.setStroke(getStroke());
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        setClip();
        graphics.draw(linePath);
        linePath.reset();
    }

    @Override
    public void fillPath(int windingRule) throws IOException
    {
        graphics.setComposite(getGraphicsState().getNonStrokingJavaComposite());
        Paint nonStrokingPaint = getNonStrokingPaint();
        if (nonStrokingPaint == null)
        {
            LOG.info("ColorSpace " + getGraphicsState().getNonStrokingColorSpace().getName() +
                    " doesn't provide a non-stroking color, using white instead!");
            nonStrokingPaint = Color.WHITE;
        }
        graphics.setPaint(nonStrokingPaint);
        linePath.setWindingRule(windingRule);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        setClip();
        graphics.fill(linePath);
        linePath.reset();
    }

    /**
     * Fills and then strokes the path.
     *
     * @param windingRule The winding rule this path will use.
     *
     * @throws IOException If there is an IO error while filling the path.
     */
    @Override
    public void fillAndStrokePath(int windingRule) throws IOException
    {
        // TODO can we avoid cloning the path?
        GeneralPath path = (GeneralPath)linePath.clone();
        fillPath(windingRule);
        linePath = path;
        strokePath();
    }

    @Override
    public void clip(int windingRule)
    {
        // the clipping path will not be updated until the succeeding painting operator is called
        clipWindingRule = windingRule;
    }

    @Override
    public void moveTo(float x, float y)
    {
        linePath.moveTo(x, y);
    }

    @Override
    public void lineTo(float x, float y)
    {
        linePath.lineTo(x, y);
    }

    @Override
    public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3)
    {
        linePath.curveTo(x1, y1, x2, y2, x3, y3);
    }

    @Override
    public Point2D getCurrentPoint()
    {
        return linePath.getCurrentPoint();
    }

    @Override
    public void closePath()
    {
        linePath.closePath();
    }

    @Override
    public void endPath()
    {
        if (clipWindingRule != -1)
        {
            linePath.setWindingRule(clipWindingRule);
            getGraphicsState().intersectClippingPath(linePath);
            clipWindingRule = -1;
        }
        linePath.reset();
    }
    
    public void drawImage(PDImage pdImage) throws IOException
    {
        Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
        AffineTransform at = ctm.createAffineTransform();

        if (pdImage.isStencil())
        {
            // fill the image with paint
            PDColorSpace colorSpace = getGraphicsState().getNonStrokingColorSpace();
            PDColor color = getGraphicsState().getNonStrokingColor();
            BufferedImage image = pdImage.getStencilImage(colorSpace.toPaint(renderer, color,
                                                          (int)pageSize.getHeight()));

            // draw the image
            drawBufferedImage(image, at);
        }
        else
        {
            // draw the image
            drawBufferedImage(pdImage.getImage(), at);
        }
    }

    public void drawBufferedImage(BufferedImage image, AffineTransform at) throws IOException
    {
        graphics.setComposite(getGraphicsState().getNonStrokingJavaComposite());
        setClip();
        PDSoftMask softMask = getGraphicsState().getSoftMask();
        if( softMask != null )
        {
            AffineTransform imageTransform = new AffineTransform(at);
            imageTransform.scale(1, -1);
            imageTransform.translate(0, -1);
            Paint awtPaint = new TexturePaint(image,
                    new Rectangle2D.Double(imageTransform.getTranslateX(), imageTransform.getTranslateY(),
                            imageTransform.getScaleX(), imageTransform.getScaleY()));
            awtPaint = applySoftMaskToPaint(awtPaint, softMask);
            graphics.setPaint(awtPaint);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            Rectangle2D unitRect = new Rectangle2D.Float(0, 0, 1, 1);
            graphics.fill(at.createTransformedShape(unitRect));
        }
        else
        {
            int width = image.getWidth(null);
            int height = image.getHeight(null);
            AffineTransform imageTransform = new AffineTransform(at);
            imageTransform.scale(1.0 / width, -1.0 / height);
            imageTransform.translate(0, -height);
            graphics.drawImage(image, imageTransform, null);
        }
    }

    @Override
    public void shadingFill(COSName shadingName) throws IOException
    {
        PDShading shading = getResources().getShadings().get(shadingName.getName());
        Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
        Paint paint = shading.toPaint(ctm);

        graphics.setComposite(getGraphicsState().getNonStrokingJavaComposite());
        graphics.setPaint(paint);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        graphics.setClip(null);
        lastClip = null;
        graphics.fill(getGraphicsState().getCurrentClippingPath());
    }

    @Override
    public void showTransparencyGroup(PDFormXObject form) throws IOException
    {
        TransparencyGroup group = createTransparencyGroup(form);
        group.draw();
    }

    private TransparencyGroup createTransparencyGroup(PDFormXObject form) throws IOException
    {
        saveGraphicsState();
        try
        {
            PDResources pdResources = form.getResources();
            if (pdResources == null)
            {
                pdResources = getResources();
            }

            // if there is an optional form matrix, we have to map the form space to the user space
            Matrix matrix = form.getMatrix();
            if(matrix != null)
            {
                Matrix xCTM = matrix.multiply(getGraphicsState().getCurrentTransformationMatrix());
                getGraphicsState().setCurrentTransformationMatrix(xCTM);
            }

            PDRectangle bBox = form.getBBox();
            GeneralPath path = transformedPDRectanglePath(bBox);
            return new TransparencyGroup(path, pdResources, form.getCOSStream());
        }
        finally
        {
            restoreGraphicsState();
        }
    }

    /**
     * Transparency group.
     **/
    private final class TransparencyGroup
    {
        private final BufferedImage image;
        private final Matrix matrix;

        private final int minX;
        private final int minY;
        private final int width;
        private final int height;

        /**
         * Creates a buffered image for a transparency group result.
         *
         * @param clippingPath clipping path (in current graphics2D coordinates)
         * @param resources Global resources
         * @param content Content of the transparency group to create
         */
        private TransparencyGroup(GeneralPath clippingPath, PDResources resources,
                                  COSStream content) throws IOException
        {
            Graphics2D g2dOriginal = graphics;
            Area lastClipOriginal = lastClip;

            // check underlying g2d

            Area groupClip = new Area(getGraphicsState().getCurrentClippingPath());
            if (clippingPath != null)
            {
                Area newArea = new Area(clippingPath);            
                groupClip.intersect(newArea);
            }

            AffineTransform at = g2dOriginal.getTransform();
            Shape clippingPathInPixels = at.createTransformedShape(groupClip);
            Rectangle2D bounds2D = clippingPathInPixels.getBounds2D();

            minX = (int) Math.floor(bounds2D.getMinX());
            minY = (int) Math.floor(bounds2D.getMinY());
            int maxX = (int) Math.floor(bounds2D.getMaxX()) + 1;
            int maxY = (int) Math.floor(bounds2D.getMaxY()) + 1;

            width = maxX - minX;
            height = maxY - minY;
            image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB); // FIXME - color space
            Graphics2D g = image.createGraphics();
            g.translate(-minX, -minY);
            g.transform(at);
            g.setClip(groupClip);

            AffineTransform atInv;
            Matrix matrix1 = null;
            try
            {
                atInv = g.getTransform().createInverse();
                atInv.scale(width, -height);
                atInv.translate(0, -1);
                matrix1 = new Matrix();
                matrix1.setFromAffineTransform(atInv);
            }
            catch (NoninvertibleTransformException e)
            {
                LOG.warn("Non-invertible transform when rendering a transparency group.", e);
            }
            matrix = matrix1;

            PDGraphicsState state = getGraphicsState();
            state.setBlendMode(BlendMode.NORMAL);
            state.setAlphaConstants(1.0);
            state.setNonStrokeAlphaConstants(1.0);
            state.setSoftMask(null);
            graphics = g;
            try
            {
                processSubStream(resources, content);
            }
            finally 
            {
                lastClip = lastClipOriginal;                
                graphics.dispose();
                graphics = g2dOriginal;
            }
        }

        public BufferedImage getImage()
        {
            return image;
        }

        public Matrix getMatrix()
        {
            return matrix;
        }

        public void draw() throws IOException
        {
            if (matrix != null)
            {
                saveGraphicsState();
                drawBufferedImage(image, matrix.createAffineTransform());
                restoreGraphicsState();
            }
        }

        public Raster getAlphaRaster()
        {
            return image.getAlphaRaster().createTranslatedChild(minX, minY);
        }

        public Raster getLuminosityRaster()
        {
            BufferedImage gray = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
            Graphics g = gray.getGraphics();
            g.drawImage(image, 0, 0, null);
            g.dispose();

            WritableRaster result = gray.getRaster();
            return result.createTranslatedChild(minX, minY);
        }
    }
}
