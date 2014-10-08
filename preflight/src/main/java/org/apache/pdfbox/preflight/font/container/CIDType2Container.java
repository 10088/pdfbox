/*****************************************************************************
 * 
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * 
 ****************************************************************************/

package org.apache.pdfbox.preflight.font.container;

import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType2;
import org.apache.pdfbox.preflight.PreflightConstants;
import org.apache.pdfbox.preflight.font.util.CIDToGIDMap;
import org.apache.pdfbox.preflight.font.util.FontLike;
import org.apache.pdfbox.preflight.font.util.GlyphException;

import java.io.IOException;

public class CIDType2Container extends FontContainer
{
    protected CIDToGIDMap cidToGid = null;
    protected TrueTypeFont ttf = null;

    public CIDType2Container(PDCIDFontType2 font)
    {
        super(new FontLike(font));
    }

    @Override
    protected float getFontProgramWidth(int cid) throws GlyphException
    {
        float foundWidth = -1;
        final int glyphIndex = getGlyphIndex(cid);

        try
        {
            // if glyph exists we can check the width
            if (this.ttf != null && this.ttf.getMaximumProfile().getNumGlyphs() > glyphIndex)
            {
            /*
             * In a Mono space font program, the length of the AdvanceWidth array must be one. According to the TrueType
             * font specification, the Last Value of the AdvanceWidth array is apply to the subsequent glyphs. So if the
             * GlyphId is greater than the length of the array the last entry is used.
             */
                int numberOfLongHorMetrics = ttf.getHorizontalHeader().getNumberOfHMetrics();
                int unitsPerEm = ttf.getHeader().getUnitsPerEm();
                int[] advanceGlyphWidths = ttf.getHorizontalMetrics().getAdvanceWidth();
                float glypdWidth = advanceGlyphWidths[numberOfLongHorMetrics - 1];
                if (glyphIndex < numberOfLongHorMetrics)
                {
                    glypdWidth = advanceGlyphWidths[glyphIndex];
                }
                foundWidth = ((glypdWidth * 1000) / unitsPerEm);
            }
            return foundWidth;
        }
        catch (IOException e)
        {
            throw new GlyphException(PreflightConstants.ERROR_FONTS_GLYPH, cid, "Unexpected error during the width validation for the character CID(" + cid+") : " + e.getMessage());
        }
    }

    /**
     * If CIDToGID map is Identity, the GID equals to the CID. Otherwise the conversion is done by the CIDToGID map
     * 
     * @param cid
     * @return -1 CID doesn't match with a GID
     */
    private int getGlyphIndex(int cid)
    {
        int glyphIndex = cid;
        if (this.cidToGid != null)
        {
            glyphIndex = cidToGid.getGID(cid);
            if (glyphIndex == cidToGid.NOTDEF_GLYPH_INDEX)
            {
                glyphIndex = -14;
            }
        }
        return glyphIndex;
    }

    public void setCidToGid(CIDToGIDMap cidToGid)
    {
        this.cidToGid = cidToGid;
    }

    public void setTtf(TrueTypeFont ttf)
    {
        this.ttf = ttf;
    }

}
