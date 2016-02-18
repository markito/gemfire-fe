package io.pivotal.bds.gemfire.r.shell.antlr;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.client.Pool;
import com.gemstone.gemfire.cache.execute.FunctionService;
import com.gemstone.gemfire.cache.execute.ResultCollector;
import com.gemstone.gemfire.cache.query.Query;
import com.gemstone.gemfire.cache.query.QueryService;
import com.gemstone.gemfire.cache.query.SelectResults;
import com.gemstone.gemfire.cache.query.Struct;
import com.gemstone.gemfire.pdx.PdxInstance;

import io.pivotal.bds.gemfire.ml.ModelName;
import io.pivotal.bds.gemfire.ml.ModelType;
import io.pivotal.bds.gemfire.r.common.AdhocPrediction;
import io.pivotal.bds.gemfire.r.common.AdhocPredictionRequest;
import io.pivotal.bds.gemfire.r.common.AdhocPredictionResponse;
import io.pivotal.bds.gemfire.r.common.EvaluateDef;
import io.pivotal.bds.gemfire.r.common.EvaluateKey;
import io.pivotal.bds.gemfire.r.common.ModelDef;
import io.pivotal.bds.gemfire.r.common.ModelKey;
import io.pivotal.bds.gemfire.r.shell.antlr.ShellParser.EvaluateContext;
import io.pivotal.bds.gemfire.r.shell.antlr.ShellParser.ExecuteContext;
import io.pivotal.bds.gemfire.r.shell.antlr.ShellParser.FieldVarContext;
import io.pivotal.bds.gemfire.r.shell.antlr.ShellParser.GpContext;
import io.pivotal.bds.gemfire.r.shell.antlr.ShellParser.LsContext;
import io.pivotal.bds.gemfire.r.shell.antlr.ShellParser.PredictContext;
import io.pivotal.bds.gemfire.r.shell.antlr.ShellParser.PrintContext;
import io.pivotal.bds.gemfire.r.shell.antlr.ShellParser.QueryArgContext;
import io.pivotal.bds.gemfire.r.shell.antlr.ShellParser.QueryArgsContext;
import io.pivotal.bds.gemfire.r.shell.antlr.ShellParser.QueryContext;
import io.pivotal.bds.gemfire.r.shell.antlr.ShellParser.SvmContext;

public class ShellListenerImpl extends ShellBaseListener {

    private PrintStream stdout;
    private QueryService queryService;
    private int queryLimit;
    private Pool pool;

    private Region<String, String> queryRegion;
    private Region<ModelKey, ModelDef> modelDefRegion;
    private Region<EvaluateKey, EvaluateDef> evaluateDefRegion;

    public ShellListenerImpl(PrintStream stdout, QueryService queryService, int queryLimit, Pool pool,
            Region<String, String> queryRegion, Region<ModelKey, ModelDef> modelDefRegion,
            Region<EvaluateKey, EvaluateDef> evaluateDefRegion) {
        this.stdout = stdout;
        this.queryService = queryService;
        this.queryLimit = queryLimit;
        this.pool = pool;
        this.queryRegion = queryRegion;
        this.modelDefRegion = modelDefRegion;
        this.evaluateDefRegion = evaluateDefRegion;
    }

    @Override
    public void exitEvaluate(EvaluateContext ctx) {
        String modelId = ctx.modelVar().getText();
        ModelKey modelKey = new ModelKey(modelId);
        ModelDef modelDef = modelDefRegion.get(modelKey);
        Assert.notNull(modelDef, "Model " + modelId + " does not exist");

        String evalId = ctx.evaluateVar().getText();
        String regionName = ctx.regionVar().getText();

        List<String> fieldNames = convertFieldVar(ctx.fieldVar());
        String[] fns = fieldNames.toArray(new String[fieldNames.size()]);

        EvaluateKey evalKey = new EvaluateKey(evalId, modelId);
        EvaluateDef evalDef = new EvaluateDef(modelKey, regionName, fns);
        evaluateDefRegion.put(evalKey, evalDef);
    }

    private List<String> convertFieldVar(List<FieldVarContext> fieldVar) {
        List<String> list = new ArrayList<>();

        if (fieldVar != null) {
            for (FieldVarContext fvc : fieldVar) {
                String s = fvc.getText();
                list.add(s);
            }
        }

        return list;
    }

    @Override
    public void exitQuery(QueryContext ctx) {
        String queryVar = ctx.queryVar().getText();
        String query = ctx.queryString().getText();
        query = query.substring(1, query.length() - 1);
        queryRegion.put(queryVar, query);
    }

    @Override
    public void exitExecute(ExecuteContext ctx) {
        String queryVar = ctx.queryVar().getText();
        String query = queryRegion.get(queryVar);
        Assert.hasText(query, "Query " + queryVar + " does not exist");

        List<QueryArgContext> argCtxs = ctx.queryArg();
        List<Object> args = new ArrayList<>();

        for (QueryArgContext ac : argCtxs) {
            String sd = ac.DECIMAL() == null ? null : ac.DECIMAL().getText();

            if (StringUtils.hasText(sd)) {
                Double d = new Double(sd);
                args.add(d);
            }

            String si = ac.INTEGER() == null ? null : ac.INTEGER().getText();

            if (StringUtils.hasText(si)) {
                Integer i = new Integer(si);
                args.add(i);
            }

            String qs = ac.QUOTEDSTRING() == null ? null : ac.QUOTEDSTRING().getText();

            if (StringUtils.hasText(qs)) {
                qs = qs.substring(1, qs.length() - 1); // strip off quotes

                if (StringUtils.hasText(qs)) {
                    args.add(qs);
                }
            }
        }

        try {
            Query q = queryService.newQuery(query);
            Object o = args.isEmpty() ? q.execute() : q.execute(args.toArray());
            SelectResults<?> res = (SelectResults<?>) o;
            List<?> list = res == null || res.isEmpty() ? null : res.asList();

            if (list == null || list.isEmpty()) {
                stdout.println("<no results>");
            } else {
                Object oh = list.get(0);

                if (oh instanceof Struct) {
                    Struct st = (Struct) oh;
                    String[] fieldNames = st.getStructType().getFieldNames();

                    // print header
                    for (int i = 0; i < fieldNames.length; ++i) {
                        if (i > 0) {
                            stdout.print("\t");
                        }

                        stdout.print(fieldNames[i]);
                    }

                    stdout.println();
                    stdout.println("============================================================================================");

                    // print rows
                    for (int ir = 0; ir < queryLimit && ir < list.size(); ++ir) {
                        st = (Struct) list.get(ir);

                        for (int i = 0; i < fieldNames.length; ++i) {
                            if (i > 0) {
                                stdout.print("\t");
                            }

                            Object fo = st.get(fieldNames[i]);
                            stdout.print(fo);
                        }

                        stdout.println();
                    }
                } else if (oh instanceof PdxInstance) {
                    PdxInstance inst = (PdxInstance) oh;
                    List<String> fieldNames = inst.getFieldNames();

                    // print header
                    for (int i = 0; i < fieldNames.size(); ++i) {
                        if (i > 0) {
                            stdout.print("\t");
                        }

                        stdout.print(fieldNames.get(i));
                    }

                    stdout.println();
                    stdout.println("============================================================================================");

                    // print rows
                    for (int ir = 0; ir < queryLimit && ir < list.size(); ++ir) {
                        inst = (PdxInstance) list.get(ir);

                        for (int i = 0; i < fieldNames.size(); ++i) {
                            if (i > 0) {
                                stdout.print("\t");
                            }

                            Object fo = inst.getField(fieldNames.get(i));
                            stdout.print(fo);
                        }

                        stdout.println();
                    }
                } else {
                    for (Object ot : list) {
                        stdout.println(ot);
                    }
                }
            }
        } catch (Exception x) {
            throw new IllegalArgumentException("Exception when executing query: " + x.getMessage(), x);
        }
    }

    @Override
    public void exitSvm(SvmContext ctx) {
        String queryVar = ctx.queryVar().getText();
        String query = queryRegion.get(queryVar);
        Assert.hasText(query, "Query " + queryVar + " does not exist");

        String modelVar = ctx.modelVar().getText();

        Map<String, Object> params = new HashMap<>();

        String scp = ctx.cpVar() == null ? null : ctx.cpVar().getText();
        if (StringUtils.hasText(scp)) {
            params.put("cp", new Double(scp));
        }

        String scn = ctx.cnVar() == null ? null : ctx.cnVar().getText();
        if (StringUtils.hasText(scn)) {
            params.put("cn", new Double(scn));
        }

        List<Object> queryArgs = new ArrayList<>();

        List<QueryArgContext> qargctx = ctx.queryArgs() == null ? null : ctx.queryArgs().queryArg();

        if (qargctx != null) {
            for (QueryArgContext qa : qargctx) {
                String sd = qa.DECIMAL().getText();
                if (StringUtils.hasText(sd)) {
                    queryArgs.add(new Double(sd));
                }

                String si = qa.INTEGER().getText();
                if (StringUtils.hasText(si)) {
                    queryArgs.add(new Integer(si));
                }

                String ss = qa.QUOTEDSTRING().getText();
                if (StringUtils.hasText(ss)) {
                    ss = ss.substring(1, ss.length() - 1);
                    if (StringUtils.hasText(ss)) {
                        queryArgs.add(ss);
                    }
                }
            }
        }

        ModelKey key = new ModelKey(modelVar);
        ModelDef info = new ModelDef(key, queryVar, ModelType.classification, ModelName.SVM, queryArgs, params);

        modelDefRegion.put(key, info);
    }

    @Override
    public void exitGp(GpContext ctx) {
        String queryVar = ctx.queryVar().getText();
        String query = queryRegion.get(queryVar);
        Assert.hasText(query, "Query " + queryVar + " does not exist");

        String modelVar = ctx.modelVar().getText();

        Map<String, Object> params = new HashMap<>();

        String sl = ctx.lambdaVar() == null ? null : ctx.lambdaVar().getText();
        if (StringUtils.hasText(sl)) {
            params.put("lambda", new Double(sl));
        }

        List<Object> queryArgs = convert(ctx.queryArgs());

        ModelKey key = new ModelKey(modelVar);
        ModelDef info = new ModelDef(key, queryVar, ModelType.regression, ModelName.GaussianProcess, queryArgs, params);

        modelDefRegion.put(key, info);
    }

    @Override
    public void exitPredict(PredictContext ctx) {
        String modelVar = ctx.modelVar().getText();
        ModelKey modelKey = new ModelKey(modelVar);
        ModelDef modelDef = modelDefRegion.get(modelKey);
        Assert.notNull(modelDef, "Model " + modelVar + " does not exist");

        String queryVar = ctx.queryVar().getText();
        String query = queryRegion.get(queryVar);
        Assert.hasText(query, "Query " + queryVar + " does not exist");

        List<Object> qargs = convert(ctx.queryArg());
        AdhocPredictionRequest req = new AdhocPredictionRequest(modelKey, queryVar, qargs);

        ResultCollector<?, ?> coll = FunctionService.onServer(pool).withArgs(req).execute("AdhocPredictionFunction");

        @SuppressWarnings("unchecked")
        List<AdhocPredictionResponse> lresp = (List<AdhocPredictionResponse>) coll.getResult();
        Assert.isTrue(!lresp.isEmpty(), "No response");

        AdhocPredictionResponse resp = lresp.get(0);

        for (AdhocPrediction pres : resp.getResults()) {
            stdout.println("x = " + Arrays.toString(pres.getX()) + " y = " + pres.getP());
        }
    }

    private List<Object> convert(List<QueryArgContext> queryArg) {
        List<Object> queryArgs = new ArrayList<>();

        if (queryArg != null) {
            for (QueryArgContext qa : queryArg) {
                String sd = qa.DECIMAL() == null ? null : qa.DECIMAL().getText();
                if (StringUtils.hasText(sd)) {
                    queryArgs.add(new Double(sd));
                }

                String si = qa.INTEGER() == null ? null : qa.INTEGER().getText();
                if (StringUtils.hasText(si)) {
                    queryArgs.add(new Integer(si));
                }

                String ss = qa.QUOTEDSTRING() == null ? null : qa.QUOTEDSTRING().getText();
                if (StringUtils.hasText(ss)) {
                    ss = ss.substring(1, ss.length() - 1);
                    if (StringUtils.hasText(ss)) {
                        queryArgs.add(ss);
                    }
                }
            }
        }

        return queryArgs;
    }

    private List<Object> convert(QueryArgsContext qac) {
        List<QueryArgContext> qargctx = qac == null ? null : qac.queryArg();
        return convert(qargctx);
    }

    @Override
    public void exitPrint(PrintContext ctx) {
        String var = ctx.var().getText();

        boolean found = printQueryVar(var);
        found = printModelVar(var) || found;
        found = printEvalVar(var) || found;

        if (!found) {
            stdout.println("<variable not found>");
        }
    }

    private boolean printEvalVar(String evalId) {
        EvaluateKey key = new EvaluateKey(evalId, "");
        EvaluateDef def = evaluateDefRegion.get(key);
        
        if (def == null) {
            return false;
        } else {
            stdout.println("Evaluate:");
            stdout.println("   model  = " + def.getModelKey().getModelId());
            stdout.println("   region = " + def.getRegionName());
            stdout.println("   fields = " + Arrays.toString(def.getFieldNames()));
            return true;
        }
    }

    private boolean printModelVar(String modelId) {
        ModelKey mk = new ModelKey(modelId);
        ModelDef info = modelDefRegion.get(mk);

        if (info == null) {
            return false;
        } else {
            stdout.println("Model:");
            stdout.println("   name  = " + info.getModelName());
            stdout.println("   type  = " + info.getModelType());
            stdout.println("   query = " + info.getQueryId());

            Map<String, Object> params = info.getParameters();
            List<String> pnames = new ArrayList<>(params.keySet());
            Collections.sort(pnames);

            for (String pn : pnames) {
                Object pv = params.get(pn);
                stdout.println("   " + pn + " = " + pv);
            }

            return true;
        }
    }

    private boolean printQueryVar(String queryId) {
        String query = queryRegion.get(queryId);

        if (query == null) {
            return false;
        } else {
            stdout.println("Query:");
            stdout.println("   " + queryId + ": " + query);
            return true;
        }
    }

    @Override
    public void exitLs(LsContext ctx) {
        stdout.println("Query:");
        printQueryVars(queryRegion.keySetOnServer());

        stdout.println("Model:");
        printModelVars(modelDefRegion.keySetOnServer());

        stdout.println("Evaluate:");
        printEvalVars(evaluateDefRegion.keySetOnServer());
    }

    private void printEvalVars(Set<EvaluateKey> c) {
        List<String> l = new ArrayList<>();
        for (EvaluateKey ek : c) {
            l.add(ek.getEvaluateId());
        }
        Collections.sort(l);
        for (String s : l) {
            stdout.println("   " + s);
        }
    }

    private void printQueryVars(Set<String> c) {
        List<String> l = new ArrayList<>(c);
        Collections.sort(l);
        for (String s : l) {
            stdout.println("   " + s);
        }
    }

    private void printModelVars(Set<ModelKey> c) {
        List<String> l = new ArrayList<>();
        for (ModelKey k : c) {
            l.add(k.getModelId());
        }
        Collections.sort(l);
        for (String s : l) {
            stdout.println("   " + s);
        }
    }

}