/*
 * Copyright (C) 2010 Brockmann Consult GmbH (info@brockmann-consult.de)
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

package org.esa.beam.pixex;

import org.esa.beam.framework.datamodel.GeoPos;
import org.esa.beam.framework.datamodel.ProductData;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;

/**
 * Reads all measurements from a specified directory. All measurement files are combined.
 */
public class MeasurementReader implements Iterator<Measurement>, Closeable {

    private int readerIndex;
    private boolean withExpression;
    private BufferedReader[] bufferedReaders;
    private String measurementLine;

    public MeasurementReader(File inputDir) throws IOException {
        readerIndex = 0;
        bufferedReaders = initReader(inputDir.listFiles(new MeasurementFilenameFilter()));
        measurementLine = getNextMeasurementLine();
    }

    @SuppressWarnings({"IOResourceOpenedButNotSafelyClosed"})
    private BufferedReader[] initReader(File[] measurementFiles) throws IOException {
        BufferedReader[] readers = new BufferedReader[measurementFiles.length];
        for (int i = 0; i < measurementFiles.length; i++) {
            File measurementFile = measurementFiles[i];
            readers[i] = new BufferedReader(new FileReader(measurementFile));
            String line = readers[i].readLine();
            while (line.startsWith("#") || line.isEmpty()) {
                line = readers[i].readLine();
            }
            withExpression = line.toLowerCase().startsWith("expression");
        }

        return readers;
    }

    @Override
    public boolean hasNext() {
        return measurementLine != null;
    }

    @Override
    public Measurement next() {
        final Scanner scanner = new Scanner(measurementLine);
        scanner.useLocale(Locale.ENGLISH);
        scanner.useDelimiter("\t");
        boolean isValid = true;
        if (withExpression) {
            isValid = scanner.nextBoolean();
        }
        final int productId = scanner.nextInt();
        final int coordId = scanner.nextInt();
        final String name = scanner.next();
        final float lat = scanner.nextFloat();
        final float lon = scanner.nextFloat();
        final float pixelX = scanner.nextFloat();
        final float pixelY = scanner.nextFloat();
        final String date = scanner.next().trim();
        final String time = scanner.next().trim();
        ProductData.UTC dateTime = null;
        if (!date.isEmpty() && !time.isEmpty()) {
            try {
                dateTime = ProductData.UTC.parse(date + " " + time, "yyyy-MM-dd HH:mm:ss");
            } catch (ParseException e) {
                e.printStackTrace();
            }
        }
        List<Number> valueList = new ArrayList<Number>();
        while (scanner.hasNextFloat()) {
            valueList.add(scanner.nextFloat());
        }

        final Number[] values = valueList.toArray(new Number[valueList.size()]);
        measurementLine = getNextMeasurementLine();
        return new Measurement(coordId, name, productId, pixelX, pixelY, dateTime,
                               new GeoPos(lat, lon), values, isValid);
    }


    private String getNextMeasurementLine() {
        BufferedReader reader = bufferedReaders[readerIndex];
        String line;
        try {
            line = reader.readLine();
            if (line == null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
                if (++readerIndex < bufferedReaders.length) {
                    reader = bufferedReaders[readerIndex];
                    line = reader.readLine();
                }
            }
        } catch (IOException ignored) {
            return null;
        }
        return line;
    }

    @Override
    public void remove() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void close() throws IOException {
        for (BufferedReader bufferedReader : bufferedReaders) {
            bufferedReader.close();
        }
    }


    private static class MeasurementFilenameFilter implements FilenameFilter {

        @Override
        public boolean accept(File dir, String name) {
            return name.toLowerCase().endsWith("measurements.txt");
        }
    }
}
