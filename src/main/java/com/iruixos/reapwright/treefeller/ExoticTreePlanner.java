package com.iruixos.reapwright.treefeller;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.block.data.MultipleFacing;
import java.util.*;

/** Conservative single-tree inspection for Mangroves and huge Nether fungi. Read-only. */
final class ExoticTreePlanner {
    record Selection(TreeInspector.Plan inspection, Set<Block> foliage,
                     Map<Block,Material> attachments, TreeReplanter.Location planting,
                     String error, String diagnostics) {
        Selection(TreeInspector.Plan inspection, Set<Block> foliage, Map<Block,Material> attachments,
                  TreeReplanter.Location planting, String error) {
            this(inspection,foliage,attachments,planting,error,null);
        }
        boolean valid(){return error==null;}
    }
    private static final BlockFace[] FACES={BlockFace.UP,BlockFace.DOWN,BlockFace.NORTH,
            BlockFace.SOUTH,BlockFace.EAST,BlockFace.WEST};
    private static boolean loaded(Block b){return b.getWorld().isChunkLoaded(b.getX()>>4,b.getZ()>>4);}
    private static int distance2(Block a,Block b){int x=a.getX()-b.getX(),z=a.getZ()-b.getZ();return x*x+z*z;}
    private static int distance3(Block a,Block b){int x=a.getX()-b.getX(),z=a.getZ()-b.getZ(),y=a.getY()-b.getY();return x*x+y*y+z*z;}
    private static boolean within(Block b,Block root,int rad,int height){return Math.abs(b.getX()-root.getX())<=rad
            &&Math.abs(b.getZ()-root.getZ())<=rad&&Math.abs(b.getY()-root.getY())<=height;}
    private static Selection reject(String why){return new Selection(null,Set.of(),Map.of(),null,why);}
    /**
     * Root-initiated Mangrove harvest. Locate a natural trunk using only a bounded,
     * face-connected root network; never interpret a root as a freestanding trunk.
     * The ordinary trunk planner must independently accept the tree AND claim the
     * exact broken root as an attachment. Otherwise normal vanilla mining applies.
     * A root shared by two independently valid trees is deliberately ambiguous.
     */
    Selection scanFromMangroveRoot(Block clicked, boolean harvestFoliage) {
        if (!rootMaterial(clicked.getType())) return reject("NOT_MANGROVE_ROOT");
        final int horizontal = 12, vertical = 20, rootLimit = 384;
        Set<Block> roots = new HashSet<>();
        ArrayDeque<Block> frontier = new ArrayDeque<>();
        Set<Block> stems = new LinkedHashSet<>();
        roots.add(clicked);
        frontier.add(clicked);
        while (!frontier.isEmpty()) {
            Block current = frontier.removeFirst();
            for (BlockFace face : FACES) {
                Block adjacent = current.getRelative(face);
                if (!within(adjacent, clicked, horizontal, vertical)) continue;
                // Never load a chunk as a side effect of a root break.
                if (!loaded(adjacent)) continue;
                Material material = adjacent.getType();
                if (material == Material.MANGROVE_LOG) {
                    stems.add(adjacent);
                } else if (rootMaterial(material) && roots.add(adjacent)) {
                    if (roots.size() > rootLimit) return reject("MANGROVE_ROOT_NETWORK_LIMIT");
                    frontier.add(adjacent);
                }
            }
        }
        if (stems.isEmpty()) return reject("MANGROVE_ROOT_NO_TRUNK");
        List<Block> ordered = new ArrayList<>(stems);
        ordered.sort(Comparator.comparingInt(stem -> distance3(stem, clicked)));
        Selection found = null;
        int distinctInspections = 0;
        Set<Block> inspectedStems = new HashSet<>();
        for (Block stem : ordered) {
            if (inspectedStems.contains(stem)) continue;
            // A few independent trunks are enough to establish ambiguity. Avoid
            // repeated full canopy scans across an unusually connected root bed.
            if (++distinctInspections > 6) return reject("AMBIGUOUS_MANGROVE_ROOT_NETWORK");
            Selection trial = scan(stem, harvestFoliage);
            if (!trial.valid()) continue;
            inspectedStems.addAll(trial.inspection().logs());
            if (!trial.attachments().containsKey(clicked)) continue;
            if (found != null && !found.inspection().logs().equals(trial.inspection().logs()))
                return reject("AMBIGUOUS_MANGROVE_ROOT_NETWORK");
            found = trial;
        }
        return found != null ? found : reject("MANGROVE_ROOT_NOT_OWNED_BY_TRUNK");
    }

    Selection scan(Block clicked,boolean harvestFoliage){
        Material stem=clicked.getType();boolean mangrove=stem==Material.MANGROVE_LOG;
        boolean crimson=stem==Material.CRIMSON_STEM, warped=stem==Material.WARPED_STEM;
        if(!mangrove&&!crimson&&!warped)return reject("UNKNOWN_EXOTIC_STEM");
        String species=mangrove?"MANGROVE":crimson?"CRIMSON":"WARPED";
        Material canopyType=mangrove?Material.MANGROVE_LEAVES:
                crimson?Material.NETHER_WART_BLOCK:Material.WARPED_WART_BLOCK;
        int radius=mangrove?13:8, vertical=mangrove?55:38;
        Block root=clicked;
        while(root.getY()>root.getWorld().getMinHeight()+1 && clicked.getY()-root.getY()<vertical
                && root.getRelative(BlockFace.DOWN).getType()==stem)root=root.getRelative(BlockFace.DOWN);
        // Competing trunks are grounded columns. Nearby 3x3 fungus stems form one trunk.
        List<Block> competitors=new ArrayList<>();
        for(int x=root.getX()-radius;x<=root.getX()+radius;x++)
            for(int z=root.getZ()-radius;z<=root.getZ()+radius;z++){
                if(!root.getWorld().isChunkLoaded(x>>4,z>>4))continue;
                for(int y=Math.max(root.getWorld().getMinHeight()+1,root.getY()-10);
                        y<=Math.min(root.getWorld().getMaxHeight()-3,root.getY()+10);y++){
                    Block b=root.getWorld().getBlockAt(x,y,z);
                    if(b.getType()!=stem || b.equals(root)
                        || b.getRelative(BlockFace.DOWN).getType()==stem
                        || b.getRelative(BlockFace.UP).getType()!=stem
                        || b.getRelative(0,2,0).getType()!=stem)continue;
                    if(Math.abs(x-root.getX())<=2&&Math.abs(z-root.getZ())<=2)continue;
                    competitors.add(b);
                }
            }
        Set<Block> logs=new LinkedHashSet<>();ArrayDeque<Block> open=new ArrayDeque<>();
        logs.add(clicked);open.add(clicked);
        while(!open.isEmpty()){
            Block cur=open.removeFirst();
            // Mangrove branches frequently connect diagonally (including up/down
            // diagonals). Face-only traversal misses those logs and strands them
            // after the canopy is harvested. Keep face-only traversal for fungi.
            for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++){
                if(dx==0&&dy==0&&dz==0)continue;
                if(!mangrove && Math.abs(dx)+Math.abs(dy)+Math.abs(dz)!=1)continue;
                // Diagonal joining is permitted only in the upper trunk/crown.
                // Near the ground it could bridge adjacent rooted Mangroves.
                if(mangrove && Math.abs(dx)+Math.abs(dy)+Math.abs(dz)>1
                        && (cur.getY()<root.getY()+3 || cur.getY()+dy<root.getY()+3))continue;
                Block n=cur.getRelative(dx,dy,dz);
                if(!within(n,root,radius,vertical))continue;
                if(!loaded(n))return reject("UNLOADED_EXOTIC_TRUNK_CHUNK");
                if(n.getType()!=stem||logs.contains(n))continue;
                boolean competing=false;
                for(Block other:competitors)if(distance2(n,other)<=distance2(n,root)){competing=true;break;}
                if(competing)continue;
                if(logs.size()>= (mangrove?450:300))return reject("EXOTIC_TRUNK_LIMIT");
                logs.add(n);open.add(n);
            }
        }
        if(logs.size()<4)return reject("EXOTIC_TRUNK_TOO_SMALL");
        Set<Block> foliage=new LinkedHashSet<>();
        String mangroveDiagnostics=null;
        if(harvestFoliage){
            ArrayDeque<Block> frontier=new ArrayDeque<>();
            for(Block t:logs)for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)for(int y=-2;y<=3;y++){
                Block b=t.getRelative(x,y,z);
                if(!within(b,root,radius,vertical)||!loaded(b))continue;
                if(!crown(b,canopyType,mangrove)||!ours(b,root,competitors))continue;
                if(foliage.add(b))frontier.add(b);
            }
            while(!frontier.isEmpty()){
                Block cur=frontier.removeFirst();
                for(int x=-1;x<=1;x++)for(int y=-1;y<=1;y++)for(int z=-1;z<=1;z++){
                    if(x==0&&y==0&&z==0)continue;
                    Block b=cur.getRelative(x,y,z);
                    if(!within(b,root,radius,vertical)||!loaded(b)||foliage.contains(b))continue;
                    if(!crown(b,canopyType,mangrove)||!ours(b,root,competitors))continue;
                    if(!mangrove){int nearest=Integer.MAX_VALUE;
                        for(Block t:logs)nearest=Math.min(nearest,distance3(t,b));
                        if(nearest>16)continue; // finite 4-block fungal canopy ownership
                    }
                    if(foliage.size()>= (mangrove?2000:800))return reject("EXOTIC_FOLIAGE_LIMIT");
                    foliage.add(b);frontier.add(b);
                }
            }
            if(foliage.size()<8)return reject("INSUFFICIENT_EXOTIC_FOLIAGE");
            if(mangrove){
                // Mangrove generation can leave small log islands inside a canopy.
                // The initial log graph cannot find an island separated by leaves.
                // Reconcile before harvesting, while the untouched natural canopy
                // can still be used as evidence of ownership. Never expand from
                // the candidate into unrestricted touching trunks.
                mangroveDiagnostics=reconcileMangroveLogIslands(root, stem, canopyType, radius, vertical,
                        competitors, logs, foliage);
            }
        }
        Map<Block,Material> attachments=new LinkedHashMap<>();
        if(mangrove){
            ArrayDeque<Block> rootQueue=new ArrayDeque<>();
            for(Block t:logs)for(BlockFace f:FACES){Block b=t.getRelative(f);
                if(rootMaterial(b.getType()) && within(b,root,7,18) && ours(b,root,competitors)){
                    if(attachments.putIfAbsent(b,b.getType())==null)rootQueue.add(b);
                }
            }
            while(!rootQueue.isEmpty()){
                Block cur=rootQueue.removeFirst();
                for(BlockFace f:FACES){Block b=cur.getRelative(f);
                    if(!within(b,root,7,18)||!loaded(b)||!rootMaterial(b.getType())
                        ||!ours(b,root,competitors)||attachments.containsKey(b))continue;
                    if(attachments.size()>=380)return reject("MANGROVE_ROOT_LIMIT");
                    attachments.put(b,b.getType());rootQueue.add(b);
                }
            }
        }
        Set<Block> supports=new LinkedHashSet<>(logs);supports.addAll(foliage);
        if(mangrove){
            // Moss carpets generated on top of mangrove foliage/logs can remain
            // floating when those supporting blocks are removed without physics.
            // Only capture carpets directly above the selected tree's own blocks;
            // carpets on natural terrain, converted muddy roots and other trees stay.
            // The attachment transaction validates these blocks again before felling.
            for(Block support:supports){
                Block carpet=support.getRelative(BlockFace.UP);
                if(!loaded(carpet))continue;
                if(carpet.getType()!=Material.MOSS_CARPET || !ours(carpet,root,competitors))continue;
                attachments.putIfAbsent(carpet,Material.MOSS_CARPET);
                if(attachments.size()>=850)return reject("EXOTIC_ATTACHMENT_LIMIT");
            }
            for(var entry:new ArrayList<>(attachments.entrySet())){
                if(entry.getValue()!=Material.MANGROVE_ROOTS)continue;
                Block carpet=entry.getKey().getRelative(BlockFace.UP);
                if(!loaded(carpet))continue;
                if(carpet.getType()!=Material.MOSS_CARPET || !ours(carpet,root,competitors))continue;
                attachments.putIfAbsent(carpet,Material.MOSS_CARPET);
                if(attachments.size()>=850)return reject("EXOTIC_ATTACHMENT_LIMIT");
            }
        }
        // A Mangrove can anchor ordinary vines to the sides of logs and leaves,
        // not just directly beneath its canopy. Collect only the first vine
        // attached to one of OUR selected tree blocks, then walk vertically
        // down its own hanging column. Never flood-fill sideways through vines.
        for(Block support:supports){
            if(mangrove){
                for(BlockFace face:new BlockFace[]{BlockFace.NORTH,BlockFace.SOUTH,
                        BlockFace.EAST,BlockFace.WEST,BlockFace.DOWN}){
                    Block seed=support.getRelative(face);
                    if(!loaded(seed)||seed.getType()!=Material.VINE||!ours(seed,root,competitors))continue;
                    // Side-facing vine faces point toward their supporting block.
                    // A vine directly underneath a selected block can hang from it.
                    if(face!=BlockFace.DOWN){
                        if(!(seed.getBlockData() instanceof MultipleFacing vine)
                                || !vine.hasFace(face.getOppositeFace()))continue;
                    }
                    for(int depth=0;depth<64;depth++){
                        Block b=seed.getRelative(0,-depth,0);
                        if(!within(b,root,radius,vertical)||!loaded(b)
                                || b.getType()!=Material.VINE||!ours(b,root,competitors))break;
                        attachments.putIfAbsent(b,Material.VINE);
                        if(attachments.size()>=850)return reject("EXOTIC_ATTACHMENT_LIMIT");
                    }
                }
                Block propagule=support.getRelative(BlockFace.DOWN);
                if(loaded(propagule)&&propagule.getType()==Material.MANGROVE_PROPAGULE
                        &&ours(propagule,root,competitors))
                    attachments.putIfAbsent(propagule,Material.MANGROVE_PROPAGULE);
            }else{
                // Nether weeping vines grow downward from wart-block canopies.
                Block seed=support.getRelative(BlockFace.DOWN);
                for(int depth=0;depth<48;depth++){
                    Block b=seed.getRelative(0,-depth,0);
                    if(!loaded(b)||!within(b,root,radius,vertical)
                            || (b.getType()!=Material.WEEPING_VINES
                            &&b.getType()!=Material.WEEPING_VINES_PLANT)
                            ||!ours(b,root,competitors))break;
                    attachments.putIfAbsent(b,b.getType());
                    if(attachments.size()>=850)return reject("EXOTIC_ATTACHMENT_LIMIT");
                }
            }
        }
        Material item=mangrove?Material.MANGROVE_PROPAGULE:crimson?Material.CRIMSON_FUNGUS:Material.WARPED_FUNGUS;
        Block plant=null;Material ground=null;
        // Capture the original planting base. Huge fungi may be standing on
        // Netherrack because Nylium decayed before harvest. Replanting can
        // repair ONLY that specific base; surrounding terrain is never changed.
        for(Block t:logs){
            Block below=t.getRelative(BlockFace.DOWN);Material substrate=below.getType();
            if(mangrove&&substrate==Material.MUDDY_MANGROVE_ROOTS&&attachments.containsKey(below))
                substrate=Material.MUD;
            if(!validSoil(substrate,species))continue;
            if(plant==null || t.getY()<plant.getY()){plant=t;ground=substrate;}
        }
        TreeReplanter.Location planting=plant==null
            ? new TreeReplanter.Location(null,"NO_VALID_EXOTIC_REPLANT_BASE")
            : new TreeReplanter.Location(new TreeReplanter.Site(List.of(plant),Map.of(plant,ground),item),"READY");
        TreeInspector.Result result=new TreeInspector.Result(species,logs.size(),foliage.size(),false,true,
            "VALID_EXOTIC_TREE",0,0,0,0,0,0);
        return new Selection(new TreeInspector.Plan(result,logs,plant==null?List.of():List.of(plant),Set.of()),
            Set.copyOf(foliage),Map.copyOf(attachments),planting,null,mangroveDiagnostics);
    }
    /**
     * Detect naturally generated Mangrove log islands in the original (unmodified)
     * canopy. Minecraft 26.3's mangrove/tall_mangrove tree features use the
     * upwards_branching_trunk_placer (up to six extra branch steps in tall trees)
     * and random_spread_foliage_placer (radius 3). Leaves need not fill the
     * corridor between the branch origin and its foliage attachment point.
     *
     * Do not use this to harvest arbitrary neighbouring tree trunks: candidate
     * groups must be small, high above the base, backed by natural foliage at
     * their endpoints, and owned more strongly by the selected grounded trunk.
     */
    private static String reconcileMangroveLogIslands(Block root, Material stem, Material canopyType,
            int radius, int vertical, List<Block> competitors, Set<Block> logs, Set<Block> foliage){
        final int maxExtra=40, maxIsland=14;
        int discovered=0, inspected=0, rejectedSize=0, rejectedGrounded=0,
                rejectedCompetitor=0, rejectedFoliage=0, rejectedGeometry=0,
                rejectedBudget=0;
        // A single bounded scan rather than per-leaf cuboid searches.
        Set<Block> candidates=new LinkedHashSet<>();
        int maxY=Math.min(root.getWorld().getMaxHeight()-1,root.getY()+vertical);
        for(int x=root.getX()-radius;x<=root.getX()+radius;x++)
            for(int z=root.getZ()-radius;z<=root.getZ()+radius;z++){
                if(!root.getWorld().isChunkLoaded(x>>4,z>>4))continue;
                for(int y=root.getY()+3;y<=maxY;y++){
                    Block b=root.getWorld().getBlockAt(x,y,z);
                    if(b.getType()==stem && !logs.contains(b))candidates.add(b);
                }
            }
        Set<Block> considered=new HashSet<>();
        for(int round=0;round<3 && discovered<maxExtra;round++){
            boolean added=false;
            considered.clear();
            for(Block seed:candidates){
                if(logs.contains(seed) || !considered.add(seed))continue;
                Set<Block> island=new LinkedHashSet<>();
                ArrayDeque<Block> queue=new ArrayDeque<>();queue.add(seed);
                boolean tooLarge=false, foreign=false, grounded=false;
                while(!queue.isEmpty()){
                    Block current=queue.removeFirst();
                    if(logs.contains(current) || island.contains(current))continue;
                    island.add(current);
                    if(island.size()>maxIsland){tooLarge=true;break;}
                    if(current.getY()<root.getY()+3 || !ours(current,root,competitors)
                            || !within(current,root,radius,vertical)){
                        foreign=true;break;
                    }
                    // Do not mistake a genuine grounded trunk for an aerial branch.
                    // A vertical 3-log column by itself is NOT sufficient evidence.
                    if(groundedForeignColumn(current,root,stem)){
                        grounded=true;break;
                    }
                    for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++){
                        if(dx==0&&dy==0&&dz==0)continue;
                        Block next=current.getRelative(dx,dy,dz);
                        if(!within(next,root,radius,vertical)||!loaded(next)
                                || next.getType()!=stem || logs.contains(next))continue;
                        if(considered.add(next))queue.add(next);
                    }
                }
                inspected++;
                if(tooLarge){rejectedSize++;continue;}
                if(grounded){rejectedGrounded++;continue;}
                if(foreign){rejectedCompetitor++;continue;}
                if(discovered+island.size()>maxExtra || logs.size()+island.size()>450){
                    rejectedBudget++;continue;
                }
                // Check foliage near the isolated branch's actual endpoints,
                // not along a straight reconstructed path through empty air.
                if(!mangroveEndpointFoliage(island,canopyType,root,competitors,foliage)){
                    rejectedFoliage++;continue;
                }
                if(!mangroveEndpointReachable(island,logs,root,competitors)){
                    rejectedGeometry++;continue;
                }
                logs.addAll(island);discovered+=island.size();added=true;
            }
            if(!added)break;
            // Rescan foliage after new branches are assigned. This also improves
            // the following vine, carpet and propagule attachment selection.
            ArrayDeque<Block> open=new ArrayDeque<>();
            for(Block log:logs)
                for(int dx=-3;dx<=3;dx++)for(int dy=-3;dy<=3;dy++)for(int dz=-3;dz<=3;dz++){
                    Block b=log.getRelative(dx,dy,dz);
                    if(within(b,root,radius,vertical) && loaded(b)
                            && crown(b,canopyType,true) && ours(b,root,competitors)
                            && foliage.size()<2000 && foliage.add(b))open.add(b);
                }
            while(!open.isEmpty()){
                Block current=open.removeFirst();
                for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++){
                    if(dx==0&&dy==0&&dz==0)continue;
                    Block b=current.getRelative(dx,dy,dz);
                    if(within(b,root,radius,vertical) && loaded(b)
                            && crown(b,canopyType,true) && ours(b,root,competitors)
                            && foliage.size()<2000 && foliage.add(b))open.add(b);
                }
            }
        }
        return "Mangrove islands: candidates="+candidates.size()+" groups="+inspected
                +" acceptedLogs="+discovered+" rejected[size="+rejectedSize
                +",grounded="+rejectedGrounded+",competitor="+rejectedCompetitor
                +",endpointFoliage="+rejectedFoliage+",branchGeometry="+rejectedGeometry
                +",budget="+rejectedBudget+"]";
    }

    /** Only reject an actual separate trunk that can be traced to terrain. */
    private static boolean groundedForeignColumn(Block candidate,Block root,Material stem){
        if(Math.abs(candidate.getX()-root.getX())<=2
                &&Math.abs(candidate.getZ()-root.getZ())<=2)return false;
        int column=0;
        Block cursor=candidate;
        for(int i=0;i<18 && loaded(cursor);i++){
            Material type=cursor.getType();
            if(type==stem){column++;cursor=cursor.getRelative(BlockFace.DOWN);continue;}
            if(rootMaterial(type)){cursor=cursor.getRelative(BlockFace.DOWN);continue;}
            return column>=3 && validSoil(type,"MANGROVE");
        }
        return false;
    }

    /** Evidence must surround the island's foliage tips, not every branch step. */
    private static boolean mangroveEndpointFoliage(Set<Block> island,Material leaves,
            Block root,List<Block> competitors,Set<Block> selectedFoliage){
        int distinct=0, selected=0;
        Set<Block> found=new HashSet<>();
        for(Block log:island){
            for(int dx=-3;dx<=3;dx++)for(int dy=-3;dy<=3;dy++)for(int dz=-3;dz<=3;dz++){
                Block b=log.getRelative(dx,dy,dz);
                if(!loaded(b)||!ours(b,root,competitors)||!crown(b,leaves,true))continue;
                if(found.add(b)){
                    distinct++;
                    if(selectedFoliage.contains(b))selected++;
                }
            }
        }
        // Require natural foliage and some connection to the selected crown,
        // without requiring contiguous leaves along an imagined branch path.
        return distinct>=3 && selected>=1;
    }

    /** Branch endpoint geometry consistent with 26.3 upward-branching placement. */
    private static boolean mangroveEndpointReachable(Set<Block> island,Set<Block> selected,
            Block root,List<Block> competitors){
        for(Block tip:island){
            for(Block origin:selected){
                int x=tip.getX()-origin.getX(), z=tip.getZ()-origin.getZ();
                int y=tip.getY()-origin.getY();
                int horizontal=Math.max(Math.abs(x),Math.abs(z));
                // Tall Mangrove: up to six extra branch steps, with one extra
                // length step. Allow limited slack for irregular branching.
                if(horizontal>9 || y< -3 || y>11 || horizontal==0 && Math.abs(y)>6)continue;
                if(horizontal==0 && y==0)continue;
                // Check whether the endpoint is spatially closer to another
                // grounded Mangrove than the selected tree root.
                if(!ours(tip,root,competitors))continue;
                return true;
            }
        }
        return false;
    }
    private static boolean rootMaterial(Material m){return m==Material.MANGROVE_ROOTS||m==Material.MUDDY_MANGROVE_ROOTS;}
    private static boolean ours(Block b,Block root,List<Block> foreign){
        for(Block other:foreign)if(distance2(b,other)<=distance2(b,root))return false;
        return true;
    }
    private static boolean crown(Block b,Material type,boolean mangrove){
        if(b.getType()!=type && (mangrove||b.getType()!=Material.SHROOMLIGHT))return false;
        return !mangrove || b.getBlockData() instanceof Leaves l && !l.isPersistent();
    }
    private static boolean validSoil(Material type,String species){
        if(species.equals("CRIMSON"))return type==Material.CRIMSON_NYLIUM || type==Material.NETHERRACK;
        if(species.equals("WARPED"))return type==Material.WARPED_NYLIUM || type==Material.NETHERRACK;
        return Set.of(Material.MUD,Material.CLAY,Material.DIRT,Material.GRASS_BLOCK,
            Material.COARSE_DIRT,Material.PODZOL,Material.MOSS_BLOCK,Material.ROOTED_DIRT).contains(type);
    }
}
