/*
 * Copyright (C) 2011 Brockmann Consult GmbH (info@brockmann-consult.de)
 * 
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 * 
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 */

package org.esa.beam.dataio.geometry;

import com.bc.ceres.binding.ConversionException;
import com.bc.ceres.binding.Converter;
import com.bc.ceres.binding.ConverterRegistry;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.Point;
import org.esa.beam.framework.datamodel.GeoCoding;
import org.esa.beam.framework.datamodel.GeoPos;
import org.esa.beam.framework.datamodel.PixelPos;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;

import java.io.IOException;

/**
 * @author Olaf Danne
 * @author Thomas Storm
 */
class LatLonAndFeatureTypeStrategy implements GeometryStrategy {

    private GeoCoding geoCoding;
    private String featureTypeName;
    private double lat;
    private double lon;
    private int latIndex;
    private int lonIndex;

    LatLonAndFeatureTypeStrategy(GeoCoding geoCoding, String featureTypeName, int latIndex, int lonIndex) {
        this.geoCoding = geoCoding;
        this.featureTypeName = featureTypeName;
        this.latIndex = latIndex;
        this.lonIndex = lonIndex;
    }

    @Override
    public void setDefaultGeometry(SimpleFeatureTypeBuilder builder) {
        builder.add("geoPos", Point.class);
        builder.add("pixelPos", Point.class);
        builder.setDefaultGeometry("pixelPos");
    }

    @Override
    public void setName(SimpleFeatureTypeBuilder builder) {
        builder.setName(featureTypeName);
    }

    @Override
    public String getFeatureId(String[] tokens) {
        return tokens[0];
    }

    @Override
    public void interpretLine(String[] tokens, SimpleFeatureBuilder builder, SimpleFeatureType simpleFeatureType) throws IOException, ConversionException {
        int attributeIndex = 0;
        for (int columnIndex = 1; columnIndex < tokens.length; columnIndex++) {
            String token = tokens[columnIndex];
            if (columnIndex == latIndex) {
                lat = Double.parseDouble(token);
                attributeIndex++;
            } else if (columnIndex == lonIndex) {
                lon = Double.parseDouble(token);
                attributeIndex++;
            } else {
                token = VectorDataNodeIO.decodeTabString(token);
                Object value = null;
                if (!VectorDataNodeIO.NULL_TEXT.equals(token)) {
                    Class<?> attributeType = simpleFeatureType.getType(attributeIndex).getBinding();
                    ConverterRegistry converterRegistry = ConverterRegistry.getInstance();
                    Converter<?> converter = converterRegistry.getConverter(attributeType);
                    if (converter == null) {
                        throw new IOException(String.format("No converter for type %s found.", attributeType));
                    }
                    value = converter.parse(token);
                }
                builder.set(simpleFeatureType.getDescriptor(attributeIndex).getLocalName(), value);
                attributeIndex++;
            }
        }
        builder.set("geoPos", new GeometryFactory().createPoint(new Coordinate(lon, lat)));
        PixelPos pixelPos = geoCoding.getPixelPos(new GeoPos((float) lat, (float) lon), null);
        builder.set("pixelPos", new GeometryFactory().createPoint(new Coordinate(pixelPos.x, pixelPos.y)));
    }

    @Override
    public void transformGeoPosToPixelPos(SimpleFeature simpleFeature) {
    }

    @Override
    public int computeExpectedTokenCount(int attributeCount) {
        int expectedTokenCount = attributeCount;
        expectedTokenCount -= 2; // (lat/lon not used as attributes)
        expectedTokenCount += 1; // column for feature id
        return expectedTokenCount;
    }
}
