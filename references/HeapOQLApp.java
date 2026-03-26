package org.heapoql;

import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.eclipse.mat.SnapshotException;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.IPathsFromGCRootsComputer;
import org.eclipse.mat.snapshot.SnapshotFactory;
import org.eclipse.mat.snapshot.SnapshotInfo;
import org.eclipse.mat.snapshot.UnreachableObjectsHistogram;
import org.eclipse.mat.snapshot.model.IClass;
import org.eclipse.mat.snapshot.model.IObject;
import org.eclipse.mat.snapshot.model.IStackFrame;
import org.eclipse.mat.snapshot.model.IThreadStack;
import org.eclipse.mat.snapshot.model.FieldDescriptor;
import org.eclipse.mat.snapshot.model.GCRootInfo;
import org.eclipse.mat.util.IProgressListener;
import org.eclipse.mat.util.VoidProgressListener;
import org.eclipse.mat.snapshot.IOQLQuery;
import org.eclipse.mat.snapshot.OQLParseException;
import org.eclipse.mat.inspections.collectionextract.CollectionExtractionUtils;
import org.eclipse.mat.inspections.collectionextract.AbstractExtractedCollection;

import java.io.File;
import java.util.*;

public class HeapOQLApp implements IApplication {

    @Override
    public Object start(IApplicationContext context) throws Exception {
        String[] args = (String[]) context.getArguments().get("application.args");

        if (args == null || args.length < 2) {
            printUsage();
            return IApplication.EXIT_OK;
        }

        String dumpPath = args[0];
        String mode = args[1];

        File dumpFile = new File(dumpPath);
        if (!dumpFile.exists()) {
            System.err.println("ERROR: Dump file not found: " + dumpPath);
            return IApplication.EXIT_OK;
        }

        IProgressListener listener = new VoidProgressListener();
        ISnapshot snapshot = null;

        try {
            System.err.println("Opening snapshot: " + dumpPath);
            snapshot = SnapshotFactory.openSnapshot(dumpFile, new HashMap<>(), listener);
            System.err.println("Snapshot opened: " + snapshot.getSnapshotInfo().getUsedHeapSize() + " bytes, "
                    + snapshot.getSnapshotInfo().getNumberOfObjects() + " objects");

            switch (mode) {
                case "oql":
                    if (args.length < 3) {
                        System.err.println("ERROR: oql mode requires a query string");
                        printUsage();
                        break;
                    }
                    String query = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                    runOQL(snapshot, query);
                    break;

                case "histogram":
                    String pattern = args.length >= 3 ? args[2] : ".*";
                    runHistogram(snapshot, pattern);
                    break;

                case "instances":
                    if (args.length < 3) {
                        System.err.println("ERROR: instances mode requires a class name");
                        break;
                    }
                    runInstances(snapshot, args[2]);
                    break;

                case "fields":
                    if (args.length < 3) {
                        System.err.println("ERROR: fields mode requires a class name");
                        break;
                    }
                    runFields(snapshot, args[2]);
                    break;

                case "gc_roots":
                    if (args.length < 3) {
                        System.err.println("ERROR: gc_roots mode requires a class name or object address (0x...)");
                        break;
                    }
                    runGCRoots(snapshot, args[2]);
                    break;

                case "dominators":
                    int topN = args.length >= 3 ? Integer.parseInt(args[2]) : 20;
                    runDominators(snapshot, topN);
                    break;

                case "duplicates":
                    int minCount = args.length >= 3 ? Integer.parseInt(args[2]) : 10;
                    runDuplicateStrings(snapshot, minCount);
                    break;

                case "collection_fill":
                    if (args.length < 3) {
                        System.err.println("ERROR: collection_fill mode requires a class name");
                        break;
                    }
                    runCollectionFill(snapshot, args[2]);
                    break;

                case "unreachable":
                    runUnreachable(snapshot);
                    break;

                case "threads":
                    runThreads(snapshot);
                    break;

                case "classloaders":
                    runClassLoaders(snapshot);
                    break;

                default:
                    System.err.println("ERROR: Unknown mode: " + mode);
                    printUsage();
            }

        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            e.printStackTrace(System.err);
        } finally {
            if (snapshot != null) {
                SnapshotFactory.dispose(snapshot);
            }
        }

        return IApplication.EXIT_OK;
    }

    // ── oql ──────────────────────────────────────────────────────────────

    private void runOQL(ISnapshot snapshot, String queryStr) throws Exception {
        System.err.println("Running OQL: " + queryStr);
        IOQLQuery query = SnapshotFactory.createQuery(queryStr);
        Object result = query.execute(snapshot, new VoidProgressListener());

        if (result instanceof int[]) {
            int[] objectIds = (int[]) result;
            System.out.println("object_id\tclass\taddress\tshallow_heap\tretained_heap\tdisplay_name");
            for (int id : objectIds) {
                IObject obj = snapshot.getObject(id);
                long retained = snapshot.getRetainedHeapSize(id);
                long address = snapshot.mapIdToAddress(id);
                String displayName = obj.getClassSpecificName();
                System.out.println(id + "\t"
                        + obj.getClazz().getName() + "\t"
                        + "0x" + Long.toHexString(address) + "\t"
                        + obj.getUsedHeapSize() + "\t"
                        + retained + "\t"
                        + (displayName != null ? displayName : ""));
            }
            System.err.println("Total: " + objectIds.length + " objects");
        } else if (result instanceof IOQLQuery.Result) {
            System.out.println("Structured result returned. Use int[]-returning queries for tabular output.");
        } else if (result != null) {
            System.out.println(result.toString());
        } else {
            System.out.println("(no results)");
        }
    }

    // ── histogram ────────────────────────────────────────────────────────

    private void runHistogram(ISnapshot snapshot, String pattern) throws Exception {
        System.err.println("Histogram for pattern: " + pattern);
        System.out.println("class_name\tinstance_count\tshallow_heap\tretained_heap");

        Collection<IClass> allClasses = snapshot.getClasses();
        List<long[]> rows = new ArrayList<>();
        List<String> names = new ArrayList<>();

        for (IClass clazz : allClasses) {
            if (clazz.getName().matches(pattern) || clazz.getName().contains(pattern)) {
                int[] objectIds = clazz.getObjectIds();
                long shallowTotal = 0;
                long retainedTotal = 0;
                for (int id : objectIds) {
                    IObject obj = snapshot.getObject(id);
                    shallowTotal += obj.getUsedHeapSize();
                    retainedTotal += snapshot.getRetainedHeapSize(id);
                }
                names.add(clazz.getName());
                rows.add(new long[]{objectIds.length, shallowTotal, retainedTotal});
            }
        }

        Integer[] indices = new Integer[rows.size()];
        for (int i = 0; i < indices.length; i++) indices[i] = i;
        Arrays.sort(indices, (a, b) -> Long.compare(rows.get(b)[2], rows.get(a)[2]));

        for (int idx : indices) {
            long[] r = rows.get(idx);
            System.out.println(names.get(idx) + "\t" + r[0] + "\t" + r[1] + "\t" + r[2]);
        }
        System.err.println("Total: " + names.size() + " classes matched");
    }

    // ── instances ────────────────────────────────────────────────────────

    private void runInstances(ISnapshot snapshot, String className) throws Exception {
        System.err.println("Instances of: " + className);
        System.out.println("object_id\taddress\tshallow_heap\tretained_heap\tdisplay_name");

        Collection<IClass> classes = snapshot.getClassesByName(className, true);
        if (classes == null || classes.isEmpty()) {
            System.err.println("Class not found: " + className);
            return;
        }

        List<long[]> rows = new ArrayList<>();
        List<String[]> meta = new ArrayList<>();

        for (IClass clazz : classes) {
            int[] objectIds = clazz.getObjectIds();
            for (int id : objectIds) {
                IObject obj = snapshot.getObject(id);
                long retained = snapshot.getRetainedHeapSize(id);
                long address = snapshot.mapIdToAddress(id);
                String displayName = obj.getClassSpecificName();
                rows.add(new long[]{id, address, obj.getUsedHeapSize(), retained});
                meta.add(new String[]{displayName != null ? displayName : ""});
            }
        }

        Integer[] indices = new Integer[rows.size()];
        for (int i = 0; i < indices.length; i++) indices[i] = i;
        Arrays.sort(indices, (a, b) -> Long.compare(rows.get(b)[3], rows.get(a)[3]));

        for (int idx : indices) {
            long[] r = rows.get(idx);
            System.out.println(r[0] + "\t0x" + Long.toHexString(r[1]) + "\t" + r[2] + "\t" + r[3] + "\t" + meta.get(idx)[0]);
        }
        System.err.println("Total: " + rows.size() + " instances");
    }

    // ── fields ───────────────────────────────────────────────────────────

    private void runFields(ISnapshot snapshot, String className) throws Exception {
        System.err.println("Fields for instances of: " + className);

        Collection<IClass> classes = snapshot.getClassesByName(className, false);
        if (classes == null || classes.isEmpty()) {
            System.err.println("Class not found: " + className);
            return;
        }

        boolean headerPrinted = false;

        for (IClass clazz : classes) {
            int[] objectIds = clazz.getObjectIds();

            for (int id : objectIds) {
                IObject obj = snapshot.getObject(id);
                long retained = snapshot.getRetainedHeapSize(id);

                if (!headerPrinted) {
                    StringBuilder header = new StringBuilder("address\tretained_heap");
                    List<FieldDescriptor> fields = clazz.getFieldDescriptors();
                    for (FieldDescriptor fd : fields) {
                        header.append("\t").append(fd.getName());
                    }
                    System.out.println(header.toString());
                    headerPrinted = true;
                }

                StringBuilder row = new StringBuilder();
                row.append("0x").append(Long.toHexString(snapshot.mapIdToAddress(id)));
                row.append("\t").append(retained);

                List<FieldDescriptor> fields = clazz.getFieldDescriptors();
                for (FieldDescriptor fd : fields) {
                    Object val = obj.resolveValue(fd.getName());
                    if (val instanceof IObject) {
                        IObject refObj = (IObject) val;
                        String name = refObj.getClassSpecificName();
                        if (name != null && name.length() < 200) {
                            row.append("\t").append(name);
                        } else {
                            row.append("\t").append(refObj.getClazz().getName()).append("@0x")
                               .append(Long.toHexString(snapshot.mapIdToAddress(refObj.getObjectId())));
                        }
                    } else if (val != null) {
                        row.append("\t").append(val);
                    } else {
                        row.append("\tnull");
                    }
                }
                System.out.println(row.toString());
            }
        }
    }

    // ── gc_roots ─────────────────────────────────────────────────────────

    private void runGCRoots(ISnapshot snapshot, String target) throws Exception {
        System.err.println("GC root path for: " + target);

        int objectId;
        if (target.startsWith("0x")) {
            long addr = Long.parseUnsignedLong(target.substring(2), 16);
            objectId = snapshot.mapAddressToId(addr);
        } else {
            // Treat as class name — find first instance
            Collection<IClass> classes = snapshot.getClassesByName(target, false);
            if (classes == null || classes.isEmpty()) {
                System.err.println("Class not found: " + target);
                return;
            }
            int[] ids = classes.iterator().next().getObjectIds();
            if (ids.length == 0) {
                System.err.println("No instances of: " + target);
                return;
            }
            // Pick the instance with the largest retained heap
            objectId = ids[0];
            long maxRetained = 0;
            for (int id : ids) {
                long r = snapshot.getRetainedHeapSize(id);
                if (r > maxRetained) {
                    maxRetained = r;
                    objectId = id;
                }
            }
            System.err.println("Using largest instance (id=" + objectId + ", retained=" + maxRetained + ")");
        }

        IPathsFromGCRootsComputer computer = snapshot.getPathsFromGCRoots(objectId, null);
        System.out.println("depth\tclass\taddress\tshallow_heap\tdisplay_name");

        int pathCount = 0;
        int maxPaths = 5;
        int[] path;
        while ((path = computer.getNextShortestPath()) != null && pathCount < maxPaths) {
            pathCount++;
            System.err.println("--- Path " + pathCount + " (length " + path.length + ") ---");
            for (int depth = 0; depth < path.length; depth++) {
                int id = path[depth];
                IObject obj = snapshot.getObject(id);
                long address = snapshot.mapIdToAddress(id);
                String displayName = obj.getClassSpecificName();
                String gcRootMarker = "";
                if (snapshot.isGCRoot(id)) {
                    GCRootInfo[] rootInfos = snapshot.getGCRootInfo(id);
                    if (rootInfos != null && rootInfos.length > 0) {
                        gcRootMarker = " [GC Root: " + GCRootInfo.getTypeSetAsString(rootInfos) + "]";
                    }
                }
                System.out.println(depth + "\t"
                        + obj.getClazz().getName() + "\t"
                        + "0x" + Long.toHexString(address) + "\t"
                        + obj.getUsedHeapSize() + "\t"
                        + (displayName != null ? displayName : "") + gcRootMarker);
            }
        }
        System.err.println("Total paths shown: " + pathCount);
    }

    // ── dominators ───────────────────────────────────────────────────────

    private void runDominators(ISnapshot snapshot, int topN) throws Exception {
        System.err.println("Top " + topN + " dominators");
        System.out.println("object_id\tclass\taddress\tshallow_heap\tretained_heap\tdisplay_name");

        // Get all objects dominated by the virtual GC root (id = -1)
        // The top-level dominators are objects whose immediate dominator is -1
        int numObjects = snapshot.getSnapshotInfo().getNumberOfObjects();
        List<long[]> rows = new ArrayList<>();
        List<String[]> meta = new ArrayList<>();

        // Iterate GC roots and find top-level dominator tree entries
        int[] gcRoots = snapshot.getGCRoots();
        Set<Integer> topDominators = new LinkedHashSet<>();

        // Walk up to find objects whose immediate dominator is the root (-1)
        for (int rootId : gcRoots) {
            int domId = snapshot.getImmediateDominatorId(rootId);
            if (domId == -1) {
                topDominators.add(rootId);
            }
        }

        // Also check all objects — some top dominators may not be GC roots themselves
        // but are dominated only by the virtual root
        // For efficiency, sample if too many objects
        if (numObjects < 10_000_000) {
            for (int id = 0; id < numObjects; id++) {
                try {
                    if (snapshot.getImmediateDominatorId(id) == -1) {
                        topDominators.add(id);
                    }
                } catch (Exception e) {
                    // skip invalid ids
                }
            }
        }

        for (int id : topDominators) {
            try {
                IObject obj = snapshot.getObject(id);
                long retained = snapshot.getRetainedHeapSize(id);
                long address = snapshot.mapIdToAddress(id);
                String displayName = obj.getClassSpecificName();
                rows.add(new long[]{id, address, obj.getUsedHeapSize(), retained});
                meta.add(new String[]{obj.getClazz().getName(), displayName != null ? displayName : ""});
            } catch (Exception e) {
                // skip
            }
        }

        // Sort by retained heap descending
        Integer[] indices = new Integer[rows.size()];
        for (int i = 0; i < indices.length; i++) indices[i] = i;
        Arrays.sort(indices, (a, b) -> Long.compare(rows.get(b)[3], rows.get(a)[3]));

        int count = Math.min(topN, indices.length);
        for (int i = 0; i < count; i++) {
            int idx = indices[i];
            long[] r = rows.get(idx);
            System.out.println(r[0] + "\t" + meta.get(idx)[0] + "\t0x" + Long.toHexString(r[1])
                    + "\t" + r[2] + "\t" + r[3] + "\t" + meta.get(idx)[1]);
        }
        System.err.println("Total top-level dominators: " + topDominators.size() + ", showing top " + count);
    }

    // ── duplicates (duplicate strings) ───────────────────────────────────

    private void runDuplicateStrings(ISnapshot snapshot, int minCount) throws Exception {
        System.err.println("Finding duplicate strings (min count: " + minCount + ")");

        Collection<IClass> stringClasses = snapshot.getClassesByName("java.lang.String", false);
        if (stringClasses == null || stringClasses.isEmpty()) {
            System.err.println("java.lang.String class not found");
            return;
        }

        Map<String, int[]> valueCounts = new HashMap<>(); // value -> [count, totalShallow]
        int processed = 0;

        for (IClass clazz : stringClasses) {
            int[] objectIds = clazz.getObjectIds();
            System.err.println("Processing " + objectIds.length + " String instances...");

            for (int id : objectIds) {
                IObject obj = snapshot.getObject(id);
                String value = obj.getClassSpecificName();
                if (value != null && value.length() <= 500) {
                    int[] stats = valueCounts.get(value);
                    if (stats == null) {
                        stats = new int[]{0, 0};
                        valueCounts.put(value, stats);
                    }
                    stats[0]++;
                    stats[1] += (int) obj.getUsedHeapSize();
                }
                processed++;
                if (processed % 1_000_000 == 0) {
                    System.err.println("  processed " + processed + " strings...");
                }
            }
        }

        System.out.println("count\ttotal_shallow\tvalue");

        // Collect entries with count >= minCount
        List<Map.Entry<String, int[]>> dupes = new ArrayList<>();
        for (Map.Entry<String, int[]> entry : valueCounts.entrySet()) {
            if (entry.getValue()[0] >= minCount) {
                dupes.add(entry);
            }
        }

        // Sort by count descending
        dupes.sort((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]));

        int shown = 0;
        for (Map.Entry<String, int[]> entry : dupes) {
            if (shown >= 100) break;
            int[] stats = entry.getValue();
            // Escape tabs/newlines in value
            String safe = entry.getKey().replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r");
            if (safe.length() > 100) safe = safe.substring(0, 100) + "...";
            System.out.println(stats[0] + "\t" + stats[1] + "\t" + safe);
            shown++;
        }
        long wastedBytes = 0;
        for (Map.Entry<String, int[]> entry : dupes) {
            int[] stats = entry.getValue();
            // wasted = (count-1) * per-instance shallow size
            wastedBytes += (long)(stats[0] - 1) * (stats[1] / stats[0]);
        }
        System.err.println("Duplicate groups: " + dupes.size() + ", estimated wasted bytes: " + wastedBytes);
    }

    // ── collection_fill ──────────────────────────────────────────────────

    private void runCollectionFill(ISnapshot snapshot, String className) throws Exception {
        System.err.println("Collection fill ratio for: " + className);

        Collection<IClass> classes = snapshot.getClassesByName(className, true);
        if (classes == null || classes.isEmpty()) {
            System.err.println("Class not found: " + className);
            return;
        }

        System.out.println("object_id\taddress\tsize\tcapacity\tfill_ratio\tretained_heap");

        List<long[]> rows = new ArrayList<>();
        List<String[]> meta = new ArrayList<>();

        for (IClass clazz : classes) {
            int[] objectIds = clazz.getObjectIds();
            for (int id : objectIds) {
                IObject obj = snapshot.getObject(id);
                try {
                    AbstractExtractedCollection<?, ?> extracted = CollectionExtractionUtils.extractCollection(obj);
                    if (extracted == null) continue;

                    Integer size = extracted.size();
                    Integer capacity = extracted.hasCapacity() ? extracted.getCapacity() : null;
                    Double fillRatio = extracted.hasFillRatio() ? extracted.getFillRatio() : null;
                    long retained = snapshot.getRetainedHeapSize(id);
                    long address = snapshot.mapIdToAddress(id);

                    rows.add(new long[]{id, address,
                            size != null ? size : -1,
                            capacity != null ? capacity : -1,
                            retained});
                    meta.add(new String[]{fillRatio != null ? String.format("%.2f", fillRatio) : "n/a"});
                } catch (Exception e) {
                    // Not a recognized collection, skip
                }
            }
        }

        // Sort by retained heap descending
        Integer[] indices = new Integer[rows.size()];
        for (int i = 0; i < indices.length; i++) indices[i] = i;
        Arrays.sort(indices, (a, b) -> Long.compare(rows.get(b)[4], rows.get(a)[4]));

        for (int idx : indices) {
            long[] r = rows.get(idx);
            System.out.println(r[0] + "\t0x" + Long.toHexString(r[1]) + "\t"
                    + (r[2] >= 0 ? r[2] : "n/a") + "\t"
                    + (r[3] >= 0 ? r[3] : "n/a") + "\t"
                    + meta.get(idx)[0] + "\t" + r[4]);
        }
        System.err.println("Total: " + rows.size() + " collections analyzed");
    }

    // ── unreachable ──────────────────────────────────────────────────────

    private void runUnreachable(ISnapshot snapshot) throws Exception {
        System.err.println("Unreachable objects histogram");

        SnapshotInfo info = snapshot.getSnapshotInfo();
        Object hist = info.getProperty("unreachableObjectsHistogram");

        if (hist instanceof UnreachableObjectsHistogram) {
            UnreachableObjectsHistogram uHist = (UnreachableObjectsHistogram) hist;
            List<UnreachableObjectsHistogram.Record> records = uHist.getRecords();

            System.out.println("class_name\tobject_count\tshallow_heap");

            // Sort by shallow heap descending
            List<UnreachableObjectsHistogram.Record> sorted = new ArrayList<>(records);
            sorted.sort((a, b) -> Long.compare(b.getShallowHeapSize(), a.getShallowHeapSize()));

            long totalShallow = 0;
            int totalObjects = 0;
            for (UnreachableObjectsHistogram.Record r : sorted) {
                System.out.println(r.getClassName() + "\t" + r.getObjectCount() + "\t" + r.getShallowHeapSize());
                totalShallow += r.getShallowHeapSize();
                totalObjects += r.getObjectCount();
            }
            System.err.println("Total unreachable: " + totalObjects + " objects, " + totalShallow + " bytes");
        } else {
            System.err.println("No unreachable objects data available (dump may not include unreachable objects)");
            System.out.println("(no unreachable objects data)");
        }
    }

    // ── threads ──────────────────────────────────────────────────────────

    private void runThreads(ISnapshot snapshot) throws Exception {
        System.err.println("Thread overview");

        Collection<IClass> threadClasses = snapshot.getClassesByName("java.lang.Thread", true);
        if (threadClasses == null || threadClasses.isEmpty()) {
            System.err.println("java.lang.Thread class not found");
            return;
        }

        System.out.println("object_id\taddress\tretained_heap\tthread_name\tstack_depth");

        List<long[]> rows = new ArrayList<>();
        List<String[]> meta = new ArrayList<>();

        for (IClass clazz : threadClasses) {
            int[] objectIds = clazz.getObjectIds();
            for (int id : objectIds) {
                IObject obj = snapshot.getObject(id);
                long retained = snapshot.getRetainedHeapSize(id);
                long address = snapshot.mapIdToAddress(id);
                String name = obj.getClassSpecificName();
                if (name == null) {
                    Object nameVal = obj.resolveValue("name");
                    if (nameVal instanceof IObject) {
                        name = ((IObject) nameVal).getClassSpecificName();
                    }
                }

                IThreadStack stack = snapshot.getThreadStack(id);
                int stackDepth = 0;
                if (stack != null && stack.getStackFrames() != null) {
                    stackDepth = stack.getStackFrames().length;
                }

                rows.add(new long[]{id, address, retained, stackDepth});
                meta.add(new String[]{name != null ? name : "unnamed"});
            }
        }

        // Sort by retained heap descending
        Integer[] indices = new Integer[rows.size()];
        for (int i = 0; i < indices.length; i++) indices[i] = i;
        Arrays.sort(indices, (a, b) -> Long.compare(rows.get(b)[2], rows.get(a)[2]));

        for (int idx : indices) {
            long[] r = rows.get(idx);
            System.out.println(r[0] + "\t0x" + Long.toHexString(r[1]) + "\t" + r[2] + "\t"
                    + meta.get(idx)[0] + "\t" + r[3]);
        }
        System.err.println("Total: " + rows.size() + " threads");
    }

    // ── classloaders ─────────────────────────────────────────────────────

    private void runClassLoaders(ISnapshot snapshot) throws Exception {
        System.err.println("Class loader analysis");

        Collection<IClass> clClasses = snapshot.getClassesByName("java.lang.ClassLoader", true);
        if (clClasses == null || clClasses.isEmpty()) {
            System.err.println("java.lang.ClassLoader class not found");
            return;
        }

        System.out.println("object_id\taddress\tclassloader_class\tretained_heap\tloaded_classes\tdisplay_name");

        // Collect all classes grouped by classloader id
        Map<Integer, Integer> classCountByLoader = new HashMap<>();
        for (IClass clazz : snapshot.getClasses()) {
            int loaderId = clazz.getClassLoaderId();
            classCountByLoader.merge(loaderId, 1, Integer::sum);
        }

        List<long[]> rows = new ArrayList<>();
        List<String[]> meta = new ArrayList<>();

        for (IClass clClass : clClasses) {
            int[] objectIds = clClass.getObjectIds();
            for (int id : objectIds) {
                IObject obj = snapshot.getObject(id);
                long retained = snapshot.getRetainedHeapSize(id);
                long address = snapshot.mapIdToAddress(id);
                int loadedClasses = classCountByLoader.getOrDefault(id, 0);
                String displayName = obj.getClassSpecificName();

                rows.add(new long[]{id, address, retained, loadedClasses});
                meta.add(new String[]{obj.getClazz().getName(), displayName != null ? displayName : ""});
            }
        }

        // Sort by retained heap descending
        Integer[] indices = new Integer[rows.size()];
        for (int i = 0; i < indices.length; i++) indices[i] = i;
        Arrays.sort(indices, (a, b) -> Long.compare(rows.get(b)[2], rows.get(a)[2]));

        for (int idx : indices) {
            long[] r = rows.get(idx);
            System.out.println(r[0] + "\t0x" + Long.toHexString(r[1]) + "\t" + meta.get(idx)[0]
                    + "\t" + r[2] + "\t" + r[3] + "\t" + meta.get(idx)[1]);
        }
        System.err.println("Total: " + rows.size() + " classloaders");
    }

    // ── usage ────────────────────────────────────────────────────────────

    private void printUsage() {
        System.err.println("Usage: heap-oql <dump_file> <mode> [args...]");
        System.err.println();
        System.err.println("Modes:");
        System.err.println("  oql <query>                 Run an OQL query");
        System.err.println("  histogram <class_pattern>   Show instances and retained heap for matching classes");
        System.err.println("  instances <class_name>      List instances with retained sizes (includes subclasses)");
        System.err.println("  fields <class_name>         Show field values for each instance");
        System.err.println("  gc_roots <class|0xaddr>     Show GC root paths for an object");
        System.err.println("  dominators [N]              Show top N dominator objects (default 20)");
        System.err.println("  duplicates [min_count]      Find duplicate strings (default min 10)");
        System.err.println("  collection_fill <class>     Show collection size vs capacity");
        System.err.println("  unreachable                 Show unreachable objects histogram");
        System.err.println("  threads                     List threads with stack depths and retained sizes");
        System.err.println("  classloaders                Show classloaders and their loaded class counts");
        System.err.println();
        System.err.println("Examples:");
        System.err.println("  heap-oql dump.hprof oql \"SELECT s FROM java.lang.String s WHERE s.count > 1000\"");
        System.err.println("  heap-oql dump.hprof histogram HashMap");
        System.err.println("  heap-oql dump.hprof instances java.util.HashMap");
        System.err.println("  heap-oql dump.hprof fields com.example.MyCache");
        System.err.println("  heap-oql dump.hprof gc_roots java.util.HashMap");
        System.err.println("  heap-oql dump.hprof gc_roots 0x6074d6588");
        System.err.println("  heap-oql dump.hprof dominators 10");
        System.err.println("  heap-oql dump.hprof duplicates 50");
        System.err.println("  heap-oql dump.hprof collection_fill java.util.HashMap");
        System.err.println("  heap-oql dump.hprof unreachable");
        System.err.println("  heap-oql dump.hprof threads");
        System.err.println("  heap-oql dump.hprof classloaders");
    }

    @Override
    public void stop() {
    }
}
