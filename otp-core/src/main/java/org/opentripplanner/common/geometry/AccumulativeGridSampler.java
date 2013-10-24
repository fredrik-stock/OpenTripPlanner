/* This program is free software: you can redistribute it and/or
 modify it under the terms of the GNU Lesser General Public License
 as published by the Free Software Foundation, either version 3 of
 the License, or (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>. */

package org.opentripplanner.common.geometry;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vividsolutions.jts.geom.Coordinate;

/**
 * Helper class to fill-in a ZSampleGrid from a given loosely-defined set of sampling points.
 * 
 * The process is customized by an "accumulative" metric which gives the behavior of cumulating
 * several values onto one sampling point.
 * 
 * @author laurent
 */
public class AccumulativeGridSampler<TZ> {

    public interface AccumulativeMetric<TZ> {
        /**
         * Callback function to handle a new added sample.
         * 
         * @param C0 The initial position of the sample, as given in the addSample() call.
         * @param Cs The position of the sample on the grid, never farther away than (dX,dY)
         * @param z The z value of the initial sample, as given in the addSample() call.
         * @param zS The previous z value of the sample. Can be null if this is the first time, it's
         *        up to the caller to initialize the z value.
         * @return The modified z value for the sample.
         */
        public TZ cumulateSample(Coordinate C0, Coordinate Cs, double z, TZ zS);

        /**
         * Callback function to handle a "closing" sample (that is a sample post-created to surround
         * existing samples and provide nice and smooth edges for the algorithm).
         * 
         * @param zUp Sampled value of the up neighbor.
         * @param zDown Idem
         * @param zRight Idem
         * @param zLeft Idem
         * @return The z value for the closing sample.
         */
        public TZ closeSample(TZ zUp, TZ zDown, TZ zRight, TZ zLeft);
    }

    private static final Logger LOG = LoggerFactory.getLogger(AccumulativeGridSampler.class);

    private AccumulativeMetric<TZ> metric;

    private ZSampleGrid<TZ> sampleGrid;

    private boolean closed = false;

    /**
     * @param metric TZ data "behavior" and "metric".
     * @param size Estimated grid size
     */
    public AccumulativeGridSampler(ZSampleGrid<TZ> sampleGrid, AccumulativeMetric<TZ> metric) {
        this.metric = metric;
        this.sampleGrid = sampleGrid;
    }

    public final void addSamplingPoint(Coordinate C0, double z) {
        if (closed)
            throw new IllegalStateException("Can't add a sample after closing.");
        int[] xy = sampleGrid.getLowerLeftIndex(C0);
        int x = xy[0];
        int y = xy[1];
        @SuppressWarnings("unchecked")
        ZSamplePoint<TZ>[] ABCD = new ZSamplePoint[4];
        ABCD[0] = sampleGrid.getOrCreate(x, y);
        ABCD[1] = sampleGrid.getOrCreate(x + 1, y);
        ABCD[2] = sampleGrid.getOrCreate(x, y + 1);
        ABCD[3] = sampleGrid.getOrCreate(x + 1, y + 1);
        for (ZSamplePoint<TZ> P : ABCD) {
            Coordinate C = sampleGrid.getCoordinates(P);
            P.setZ(metric.cumulateSample(C0, C, z, P.getZ()));
        }
    }

    /**
     * Surround all existing samples on the edge by a layer of closing samples.
     */
    public final void close() {
        if (closed)
            return;
        closed = true;
        List<ZSamplePoint<TZ>> processList = new ArrayList<ZSamplePoint<TZ>>(sampleGrid.size());
        for (ZSamplePoint<TZ> A : sampleGrid) {
            processList.add(A);
        }
        int n = 0;
        for (ZSamplePoint<TZ> A : processList) {
            if (A.right() == null) {
                closeSample(A.getX() + 1, A.getY());
                n++;
            }
            if (A.left() == null) {
                closeSample(A.getX() - 1, A.getY());
                n++;
            }
            if (A.up() == null) {
                closeSample(A.getX(), A.getY() + 1);
                n++;
            }
            if (A.down() == null) {
                closeSample(A.getX(), A.getY() - 1);
                n++;
            }
        }
        LOG.info("Added {} closing samples to get a total of {}.", n, sampleGrid.size());
    }

    private final ZSamplePoint<TZ> closeSample(int x, int y) {
        ZSamplePoint<TZ> A = sampleGrid.getOrCreate(x, y);
        A.setZ(metric.closeSample(A.up() != null ? A.up().getZ() : null, A.down() != null ? A
                .down().getZ() : null, A.right() != null ? A.right().getZ() : null,
                A.left() != null ? A.left().getZ() : null));
        return A;
    }
}