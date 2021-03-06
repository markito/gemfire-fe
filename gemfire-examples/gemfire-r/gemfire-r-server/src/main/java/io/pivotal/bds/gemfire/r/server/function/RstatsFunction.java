package io.pivotal.bds.gemfire.r.server.function;

import java.util.List;
import java.util.Properties;

import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.springframework.util.Assert;

import com.gemstone.gemfire.cache.Declarable;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.execute.Function;
import com.gemstone.gemfire.cache.execute.FunctionContext;
import com.gemstone.gemfire.cache.execute.FunctionException;
import com.gemstone.gemfire.cache.execute.ResultSender;

import io.pivotal.bds.gemfire.r.common.Matrix;
import io.pivotal.bds.gemfire.r.common.MatrixKey;
import io.pivotal.bds.gemfire.r.common.RstatsRequest;
import io.pivotal.bds.gemfire.r.common.StatsResponse;
import io.pivotal.bds.gemfire.r.common.Vector;
import io.pivotal.bds.gemfire.util.RegionHelper;

@SuppressWarnings("serial")
public class RstatsFunction implements Function, Declarable {

    @Override
    public void execute(FunctionContext ctx) {
        try {
            RstatsRequest req = (RstatsRequest) ctx.getArguments();
            MatrixKey matrixKey = req.getMatrixKey();
            int r = req.getRow();

            Region<MatrixKey, Matrix<Object>> matrixRegion = RegionHelper.getRegion("matrix");
            Matrix<Object> matrix = matrixRegion.get(matrixKey);
            Assert.notNull(matrix, "Matrix " + matrixKey.getId() + " not found");

            if (matrix.getRows().size() >= r) {
                throw new IllegalArgumentException("Invalid row");
            }

            DescriptiveStatistics stats = new DescriptiveStatistics();
            Vector<Object> row = matrix.getRows().get(r);
            List<Object> l = row.getVector();

            for (Object o : l) {
                if (!Number.class.isInstance(o)) {
                    throw new IllegalArgumentException("Matrix " + matrixKey.getId() + " contains non-numerics at row " + r);
                }

                Number n = (Number) o;
                double d = n.doubleValue();
                stats.addValue(d);
            }

            ResultSender<StatsResponse> sender = ctx.getResultSender();
            StatsResponse resp = new StatsResponse(stats);
            sender.lastResult(resp);
        } catch (Exception x) {
            throw new FunctionException(x.toString(), x);
        }
    }

    @Override
    public String getId() {
        return getClass().getSimpleName();
    }

    @Override
    public boolean hasResult() {
        return true;
    }

    @Override
    public boolean isHA() {
        return false;
    }

    @Override
    public boolean optimizeForWrite() {
        return false;
    }

    @Override
    public void init(Properties props) {
    }

}
