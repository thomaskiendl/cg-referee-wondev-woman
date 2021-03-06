import java.io.*;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collector;

import static java.util.stream.Stream.of;
import static java.util.stream.Collectors.joining;

class Referee extends MultiReferee {
    public static int GAME_VERSION = 3;

    static final Pattern PLAYER_PATTERN = Pattern.compile(
            "^(?<action>MOVE\\&BUILD|PUSH\\&BUILD)\\s+(?<index>\\d)\\s+(?<move>N|S|W|E|NW|NE|SW|SE)\\s+(?<place>N|S|W|E|NW|NE|SW|SE)(?:\\s+)?(?:\\s+(?<message>.+))?",
            Pattern.CASE_INSENSITIVE);
    static final Pattern ACCEPT_DEFEAT_PATTERN = Pattern.compile(
            "^ACCEPT-DEFEAT(?:\\s+)?(?:\\s+(?<message>.+))?",
            Pattern.CASE_INSENSITIVE);

    public static final int GOT_PUSHED = 2;
    public static final int DID_PUSH = 1;
    public static final int NO_PUSH = 0;
    public static final int FINAL_HEIGHT = 4;
    public static final int VIEW_DISTANCE = 1;
    public static final int GENERATED_MAP_SIZE = 6;
    public static boolean WIN_ON_MAX_HEIGHT = true;
    public static boolean FOG_OF_WAR = false;
    public static boolean CAN_PUSH = false;
    public static int UNITS_PER_PLAYER = 1;

    public static void main(String... args) throws IOException {
        new Referee(System.in, System.out, System.err);
    }

    static class Point {
        final int x, y;

        public Point(int x, int y) {
            this.x = x;
            this.y = y;
        }

        @Override
        public String toString() {
            return "Point [x=" + x + ", y=" + y + "]";
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + x;
            result = prime * result + y;
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null) return false;
            if (getClass() != obj.getClass()) return false;
            Point other = (Point) obj;
            return x == other.x && y == other.y;
        }

        int distance(Point other) {
            return Math.max(Math.abs(x - other.x), Math.abs(y - other.y));
        }
    }

    static class Unit {
        int index;
        Player player;
        Point position;
        boolean gotPushed;
        ActionResult did;

        public Unit(Player player, int index) {
            this.player = player;
            this.index = index;
        }

        public void reset() {
            gotPushed = false;
            did = null;
        }

        public boolean pushed() {
            return did != null && did.type == Action.PUSH;
        }

        public boolean moved() {
            return did != null && did.type == Action.MOVE;
        }

    }

    static class ActionResult {

        public Point moveTarget;
        public Point placeTarget;
        public boolean placeValid;
        public boolean moveValid;
        public boolean scorePoint;
        public String type;
        public Unit unit;

        public ActionResult(String type) {
            this.type = type;
        }

    }

    static class Action {
        public static String MOVE = "MOVE&BUILD";
        public static String PUSH = "PUSH&BUILD";

        int index;
        Direction move;
        Direction place;
        String command;

        public Action(String command, int index, Direction move, Direction place) {
            this.index = index;
            this.move = move;
            this.place = place;
            this.command = command;
        }

        public String toPlayerString() {
            return command + " " + index + " " + move + " " + place;
        }
    }

    static class Grid {
        private Map<Point, Integer> map;
        int size;

        public Grid() {
            this.map = new HashMap<>();
        }

        public Integer get(int x, int y) {
            return get(new Point(x, y));
        }

        public Integer get(Point p) {
            Integer level = map.get(p);
            return level;
        }

        public void create(Point point) {
            map.put(point, 0);
            int necessarySize = Math.max(point.x, point.y) + 1;
            if (necessarySize > size) {
                size = necessarySize;
            }
        }

        public void place(Point placeAt) {
            map.put(placeAt, map.get(placeAt) + 1);
        }
    }

    static class Player {
        int index, score;
        boolean dead, won;
        List<Unit> units;
        private String message;

        public Player(int index) {
            this.index = index;
            units = new ArrayList<>();
            score = 0;
        }

        public void win() {
            won = true;
        }

        public void die(int round) {
            dead = true;
        }

        public void reset() {
            message = null;
            units.stream().forEach(Unit::reset);
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
            if (message != null && message.length() > 48) {
                this.message = message.substring(0, 46) + "...";
            }

        }
    }

    private boolean symmetric;
    private long seed;
    private Random random;
    private Grid grid;
    private List<Player> players;
    private List<Unit> units;
    private int mapIndex;
    private String expected;

    public Referee(InputStream is, PrintStream out, PrintStream err) throws IOException {
        super(is, out, err);
    }

    @Override
    protected boolean isTurnBasedGame() {
        return true;
    }

    @Override
    protected boolean gameIsOver() {
        if (WIN_ON_MAX_HEIGHT) {
            return super.gameIsOver() || players.stream().anyMatch(p -> p.won || p.dead);
        }

        boolean oneDead = players.get(0).dead;
        boolean twoDead = players.get(1).dead;
        if (oneDead && twoDead) {
            return true;
        } else if (oneDead && !twoDead) {
            return players.get(1).score > players.get(0).score;
        } else if (!oneDead && twoDead) {
            return players.get(0).score > players.get(1).score;
        } else {
            return false;
        }
    }

    @Override
    protected void initReferee(int playerCount, Properties prop) throws InvalidFormatException {
        String seed = prop.getProperty("seed", String.valueOf(new Random(System.currentTimeMillis()).nextLong()));
        String mapIndex = prop.getProperty("mapIndex", "-1");
        String symmetric = prop.getProperty("symmetric", "false");

        if (GAME_VERSION >= 1) {
            WIN_ON_MAX_HEIGHT = false;
        }
        if (GAME_VERSION >= 2) {
            UNITS_PER_PLAYER = 2;
            CAN_PUSH = true;
        }

        if (GAME_VERSION >= 3) {
            FOG_OF_WAR = true;
        }

        expected = "MOVE&BUILD";
        if (CAN_PUSH) {
            expected += " | PUSH&BUILD";
        }
        expected += " <index> <direction> <direction>";

        try {
            this.mapIndex = Integer.valueOf(mapIndex);
        } catch (NumberFormatException e) {
            this.mapIndex = -1;
        }
        this.seed = new Random(System.currentTimeMillis()).nextLong();
        try {
            this.seed = Long.valueOf(seed);
        } catch (NumberFormatException e) {
        }
        try {
            this.symmetric = Boolean.valueOf(symmetric);
        } catch (NumberFormatException e) {
            this.symmetric = false;
        }

        random = new Random(this.seed);
        grid = initGrid();
        players = new ArrayList<Player>(playerCount);
        units = new ArrayList<Unit>(playerCount * UNITS_PER_PLAYER);
        for (int idx = 0; idx < playerCount; ++idx) {
            Player player = new Player(idx);
            for (int i = 0; i < UNITS_PER_PLAYER; ++i) {
                Unit u = new Unit(player, i);
                player.units.add(u);
                units.add(u);
            }
            players.add(player);
        }

        LinkedList<Point> points = new LinkedList<>(grid.map.keySet());
        // Remove random from unordered set
        Collections.sort(points, (a, b) -> (a.x == b.x) ? a.y - b.y : a.x - b.x);
        // Introduce random from seed
        Collections.shuffle(points, random);

        if (this.symmetric) {
            Player one = players.get(0);
            Player two = players.get(1);
            Queue<Point> queue = (Queue<Point>) points;
            for (Unit u : one.units) {
                boolean okay = false;
                while (!okay) {
                    Point a = queue.poll();
                    Point b = new Point(grid.size - 1 - a.x, a.y);
                    boolean removed = queue.remove(b);
                    if (removed) {
                        okay = true;
                        u.position = a;
                        two.units.get(u.index).position = b;
                    }
                }
            }

        } else {
            for (Unit u : units) {
                u.position = ((Queue<Point>) points).poll();
            }

        }
    }

    /**
     * There is no good reason to create a custom Collector just for instantiating a Point. I just wanted to try it out.
     *
     * @author julien
     */
    static class PointCollector implements Collector<Integer, List<Integer>, Point> {
        @Override
        public Set<java.util.stream.Collector.Characteristics> characteristics() {
            return new HashSet<>();
        }

        @Override
        public Supplier<List<Integer>> supplier() {
            return LinkedList<Integer>::new;
        }

        @Override
        public BiConsumer<List<Integer>, Integer> accumulator() {
            return (list, value) -> list.add(value);
        }

        @Override
        public BinaryOperator<List<Integer>> combiner() {
            return (a, b) -> {
                a.addAll(b);
                return a;
            };
        }

        @Override
        public Function<List<Integer>, Point> finisher() {
            return list -> new Point(list.get(0), list.get(1));
        }

    }

    private PointCollector toPoint() {
        return new PointCollector();
    }

    private Grid initGrid() {
        Grid grid = new Grid();
        List<String> maps = new ArrayList<>();
        maps.add("0 0;1 0;2 0;3 0;4 0;0 1;1 1;2 1;3 1;4 1;0 2;1 2;2 2;3 2;4 2;0 3;1 3;2 3;3 3;4 3;0 4;1 4;2 4;3 4;4 4"); // Square
        maps.add("3 0;2 1;3 1;4 1;1 2;2 2;3 2;4 2;5 2;0 3;1 3;2 3;3 3;4 3;5 3;6 3;1 4;2 4;3 4;4 4;5 4;2 5;3 5;4 5;3 6"); // Diamond
        maps.add(generateRandomMap());

        int randomMapIndex = random.nextInt(maps.size());
        if (mapIndex < 0 || mapIndex >= maps.size()) {
            mapIndex = randomMapIndex;
        }
        String[] coords = maps.get(mapIndex).split(";");
        of(coords)
                .map(coord -> of(coord.split(" "))
                        .map(Integer::valueOf)
                        .collect(toPoint()))
                .forEach(point -> grid.create(point));
        return grid;
    }

    private String generateRandomMap() {
        Set<String> coords = new HashSet<>();
        int size = GENERATED_MAP_SIZE;
        int iterations = 0;
        int cells = 25 + random.nextInt(10);
        int islands = 0;
        Grid g = new Grid();
        while ((coords.size() < cells || islands > 1) && iterations < 1_000) {
            int x = random.nextInt(size);
            int y = random.nextInt(size);
            Point point = new Point(x, y);
            Point mirror = new Point((size - 1 - x), y);

            coords.add(point.x + " " + point.y);
            coords.add(mirror.x + " " + mirror.y);
            g.create(point);
            g.create(mirror);

            islands = countIslands(g, size);
            iterations++;
        }
        return coords.stream().collect(joining(";"));
    }

    private int countIslands(Grid grid, int size) {
        Set<Point> computed = new HashSet<>();

        int total = 0;
        for (Point p : grid.map.keySet()) {
            if (!computed.contains(p)) {
                total++;
                Queue<Point> fifo = new LinkedList<>();
                fifo.add(p);
                while (!fifo.isEmpty()) {
                    Point e = fifo.poll();
                    for (Direction d : Direction.values()) {
                        Point n = getNeighbor(d.name(), e, size);
                        if (!computed.contains(n) && grid.get(n) != null) {
                            fifo.add(n);
                        }
                    }
                    computed.add(e);
                }
            }
        }
        return total;
    }

    static enum Direction {
        NW, N, NE, W, E, SW, S, SE
    }

    @Override
    protected Properties getConfiguration() {
        Properties p = new Properties();
        p.put("seed", seed);
        p.put("mapIndex", mapIndex);
        if (symmetric) {
            p.put("symmetric", true);
        }
        return p;
    }

    @Override
    protected String[] getInitInputForPlayer(int playerIdx) {
        List<String> lines = new ArrayList<>();
        lines.add(String.valueOf(grid.size));
        lines.add(String.valueOf(UNITS_PER_PLAYER));
        return lines.toArray(new String[lines.size()]);
    }

    @Override
    protected String[] getInputForPlayer(int round, int playerIdx) {
        List<String> lines = new ArrayList<>();
        Player self = players.get(playerIdx);
        Player other = players.get((playerIdx + 1) % 2);

        for (int y = 0; y < grid.size; ++y) {
            StringBuilder row = new StringBuilder();
            for (int x = 0; x < grid.size; ++x) {
                Integer height = grid.get(x, y);
                if (height == null) {
                    row.append(".");
                } else {
                    row.append(height);
                }
            }
            lines.add(row.toString());
        }

        of(self, other).forEach(p -> {
            for (Unit u : p.units) {
                if (p == other && !unitVisibleToPlayer(u, self)) {
                    lines.add("-1 -1");
                } else {
                    lines.add(u.position.x + " " + u.position.y);
                }
            }
        });

        List<Action> legalActions = getLegalActions(self);
        lines.add(String.valueOf(legalActions.size()));
        for (Action action : legalActions) {
            lines.add(action.toPlayerString());
        }
        return lines.toArray(new String[lines.size()]);
    }

    private boolean unitVisibleToPlayer(Unit unit, Player player) {
        if (!FOG_OF_WAR)
            return true;
        for (Unit u : player.units) {
            if (u.position.distance(unit.position) <= VIEW_DISTANCE) {
                return true;
            }
        }
        return false;
    }

    private ActionResult computeMove(Unit unit, String dir1, String dir2) throws LostException {

        Point target = getNeighbor(dir1, unit.position);
        Integer targetHeight = grid.get(target);
        if (targetHeight == null) {
            throw new LostException("BadCoords", target.x, target.y);
        }
        int currentHeight = grid.get(unit.position);
        if (targetHeight > currentHeight + 1) {
            throw new LostException("InvalidMove", currentHeight, targetHeight);
        }
        if (targetHeight >= FINAL_HEIGHT) {
            throw new LostException("MoveTooHigh", target.x, target.y);
        }
        if (getUnitOnPoint(target).isPresent()) {
            throw new LostException("MoveOnUnit", target.x, target.y);
        }

        Point placeTarget = getNeighbor(dir2, target);
        Integer placeTargetHeight = grid.get(placeTarget);
        if (placeTargetHeight == null) {
            throw new LostException("InvalidPlace", placeTarget.x, placeTarget.y);
        }
        if (placeTargetHeight >= FINAL_HEIGHT) {
            throw new LostException("PlaceTooHigh", targetHeight);
        }

        ActionResult result = new ActionResult(Action.MOVE);
        result.moveTarget = target;
        result.placeTarget = placeTarget;

        Optional<Unit> possibleUnit = getUnitOnPoint(placeTarget).filter(u -> !u.equals(unit));
        if (!possibleUnit.isPresent()) {
            result.placeValid = true;
            result.moveValid = true;
        } else if (FOG_OF_WAR && !unitVisibleToPlayer(possibleUnit.get(), unit.player)) {
            result.placeValid = false;
            result.moveValid = true;
        } else {
            throw new LostException("PlaceOnUnit", placeTarget.x, placeTarget.y);
        }

        if (targetHeight == FINAL_HEIGHT - 1) {
            result.scorePoint = true;
        }
        result.unit = unit;
        return result;
    }

    private Optional<Unit> getUnitOnPoint(Point target) {
        Optional<Unit> potentialUnit = units.stream().filter(u -> u.position.equals(target)).findFirst();
        return potentialUnit;
    }

    private ActionResult computePush(Unit unit, String dir1, String dir2) throws LostException {
        if (!validPushDirection(dir1, dir2)) {
            throw new LostException("PushInvalid", dir1, dir2);
        }
        Point target = getNeighbor(dir1, unit.position);
        Optional<Unit> maybePushed = getUnitOnPoint(target);
        if (!maybePushed.isPresent()) {
            throw new LostException("PushVoid", target.x, target.y);
        }
        Unit pushed = maybePushed.get();

        if (pushed.player == unit.player) {
            throw new LostException("FriendlyFire", unit.index, pushed.index);
        }

        Point pushTo = getNeighbor(dir2, pushed.position);
        Integer toHeight = grid.get(pushTo);
        int fromHeight = grid.get(target);

        if (toHeight == null || toHeight >= FINAL_HEIGHT || toHeight > fromHeight + 1) {
            throw new LostException("PushInvalid", dir1, dir2);
        }

        ActionResult result = new ActionResult(Action.PUSH);
        result.moveTarget = pushTo;
        result.placeTarget = target;

        Optional<Unit> possibleUnit = getUnitOnPoint(pushTo);
        if (!possibleUnit.isPresent()) {
            result.placeValid = true;
            result.moveValid = true;
        } else if (FOG_OF_WAR && !unitVisibleToPlayer(possibleUnit.get(), unit.player)) {
            result.placeValid = false;
            result.moveValid = false;

        } else {
            throw new LostException("PushOnUnit", dir1, dir2);
        }

        result.unit = pushed;

        return result;
    }

    private ActionResult computeAction(String command, Unit unit, String dir1, String dir2) throws LostException {
        if (command.equalsIgnoreCase(Action.MOVE)) {
            return computeMove(unit, dir1, dir2);
        } else if (CAN_PUSH && command.equals(Action.PUSH)) {
            return computePush(unit, dir1, dir2);
        } else {
            throw new LostException("InvalidCommand", command);
        }
    }

    private List<Action> getLegalActions(Player player) {
        List<Action> actions = new LinkedList<>();
        for (Unit unit : player.units) {
            for (Direction dir1 : Direction.values()) {
                for (Direction dir2 : Direction.values()) {

                    try {
                        computeAction(Action.MOVE, unit, dir1.name(), dir2.name());
                        actions.add(new Action(Action.MOVE, unit.index, dir1, dir2));
                    } catch (LostException eMove) {
                    }
                    if (CAN_PUSH) {
                        try {
                            computeAction(Action.PUSH, unit, dir1.name(), dir2.name());
                            actions.add(new Action(Action.PUSH, unit.index, dir1, dir2));
                        } catch (LostException ePush) {
                        }
                    }

                }

            }
        }
        actions.sort((a, b) -> a.toPlayerString().compareTo(b.toPlayerString()));
        return actions;
    }

    @Override
    protected void prepare(int round) {
        players.stream().forEach(Player::reset);
    }

    @Override
    protected int getExpectedOutputLineCountForPlayer(int playerIdx) {
        return 1;
    }

    private void matchMessage(Player player, Matcher match) {
        player.setMessage(match.group("message"));
    }

    private Point getNeighbor(String direction, Point position) {
        return getNeighbor(direction, position, grid.size);
    }

    private Point getNeighbor(String direction, Point position, int size) {
        int x = position.x;
        int y = position.y;
        if (direction.contains("E")) {
            x++;
        } else if (direction.contains("W")) {
            x--;
        }
        if (direction.contains("S")) {
            y++;
        } else if (direction.contains("N")) {
            y--;
        }
        return new Point(x, y);
    }

    @Override
    protected void handlePlayerOutput(int frame, int round, int playerIdx, String[] outputs)
            throws WinException, LostException, InvalidInputException {
        String line = outputs[0];
        Player player = players.get(playerIdx);

        try {
            Matcher match = ACCEPT_DEFEAT_PATTERN.matcher(line);
            if (match.matches()) {
                player.die(round);
                //Message
                matchMessage(player, match);
                throw new LostException("selfDestruct", player.index);
            }
            match = PLAYER_PATTERN.matcher(line);
            if (match.matches()) {
                String action = match.group("action");
                String indexString = match.group("index");
                String dir1 = match.group("move").toUpperCase();
                String dir2 = match.group("place").toUpperCase();
                int index = Integer.valueOf(indexString);
                Unit unit = player.units.get(index);

                ActionResult ar = computeAction(action, unit, dir1, dir2);
                unit.did = ar;
                if (ar.moveValid) {
                    ar.unit.position = ar.moveTarget;
                }
                if (ar.placeValid) {
                    grid.place(ar.placeTarget);
                }
                if (ar.scorePoint) {
                    player.score++;
                }
                if (ar.type.equals(Action.PUSH)) {
                    ar.unit.gotPushed = true;
                }

                //Message
                matchMessage(player, match);
                return;
            }

            throw new InvalidInputException(expected, line);

        } catch (LostException | InvalidInputException e) {
            player.die(round);
            throw e;
        } catch (Exception e) {
            StringWriter errors = new StringWriter();
            e.printStackTrace(new PrintWriter(errors));
            printError(e.getMessage() + "\n" + errors.toString());
            player.die(round);
            throw new InvalidInputException(expected, line);
        }

    }

    private boolean validPushDirection(String target, String push) {
        if (target.length() == 2) {
            return push.equals(target) || push.equals(target.substring(0, 1)) || push.equals(target.substring(1, 2));
        } else {
            return push.contains(target);
        }
    }

    @Override
    protected void updateGame(int round) throws GameOverException {
        for (Unit unit : units) {
            if (WIN_ON_MAX_HEIGHT && grid.get(unit.position).equals(FINAL_HEIGHT - 1)) {
                unit.player.win();
            }
        }
    }

    @Override
    protected void populateMessages(Properties p) {
        //Error messages
        p.put("PlaceOnUnit", "Trying to build on a unit at position (%d,%d).");
        p.put("PushVoid", "Nobody to push at position (%d, %d).");
        p.put("PlaceTooHigh", "Cannot build at height %d.");
        p.put("InvalidMove", "Cannot move from height %d to %d.");
        p.put("MoveOnUnit", "Trying to move onto an occupied cell at (%d,%d).");
        p.put("BadCoords", "Cannot move to position (%d,%d).");
        p.put("MoveTooHigh", "Cannot move to position (%d,%d).");
        p.put("InvalidPlace", "Cannot build on position (%d,%d).");
        p.put("PushInvalid", "Not a valid push: %s + %s.");
        p.put("FriendlyFire", "Unit %d is tried to push friendly unit %d.");
        p.put("PushOnUnit", "Trying to push onto another unit: %s + %s.");
        p.put("selfDestruct", "$%d accepts defeat!");

        //Tooltip error messages
        p.put("PlaceOnUnitTooltip", "Invalid build (%d,%d)");
        p.put("PushVoidTooltip", "Invalid push (%d, %d)");
        p.put("PlaceTooHighTooltip", "Invalid build (%d,%d)");
        p.put("InvalidMoveTooltip", "Invalid move (%d to %d)");
        p.put("MoveOnUnitTooltip", "Invalid move (%d,%d)");
        p.put("BadCoordsTooltip", "Invalid move (%d,%d)");
        p.put("MoveTooHighTooltip", "Invalid move (%d,%d)");
        p.put("InvalidPlaceTooltip", "Invalid build (%d,%d)");
        p.put("PushInvalidTooltip", "Invalid push (%s + %s)");
        p.put("FriendlyFireTooltip", "Friendly fire!");
        p.put("PushOnUnitTooltip", "Invalid push (%s + %s)");
        p.put("selfDestructTooltip", "accepted defeat!");

        //Status messages
        p.put("MoveValid", "$%d moved unit %d to (%d,%d).");
        p.put("PlaceValid", "...and builds on (%d,%d).");
        p.put("CancelledPlace", "...and cannot build on (%d,%d)!");
        p.put("PushToPlaceOn", "$%d made unit %d push a unit to (%d,%d) and builds on (%d,%d).");
        p.put("CancelledPush", "$%d attempted to make unit %d push the unit on (%d, %d), but could not.");
        p.put("Wins", "...and wins the game!");
        p.put("Scores", "...and scores a point!");

    }

    @Override
    protected String[] getInitDataForView() {
        List<String> lines = new ArrayList<>();
        lines.add(String.valueOf(grid.size));
        lines.add(String.valueOf(GAME_VERSION));
        lines.add(String.valueOf(UNITS_PER_PLAYER));
        lines.add(0, String.valueOf(lines.size() + 1));
        return lines.toArray(new String[lines.size()]);
    }

    @Override
    protected String[] getFrameDataForView(int round, int frame, boolean keyFrame) {
        List<String> lines = new ArrayList<>();

        units.stream().forEach(u -> {
            int pushCode = NO_PUSH;
            if (u.pushed()) {
                pushCode = DID_PUSH;
            } else if (u.gotPushed) {
                pushCode = GOT_PUSHED;
            }

            lines.add(u.position.x + " " + u.position.y + " " + pushCode);

        });
        for (int y = 0; y < grid.size; ++y) {
            StringBuilder row = new StringBuilder();
            for (int x = 0; x < grid.size; ++x) {
                Integer height = grid.get(x, y);
                if (height == null) {
                    row.append(".");
                } else {
                    row.append(height);
                }
            }
            lines.add(row.toString());
        }
        for (Player p : players) {
            lines.add(String.valueOf(getScore(p.index)) + " " + (p.dead ? 0 : 1) + ";" + (p.message == null ? "" : p.message));
        }
        lines.add(String.valueOf(whoJustPlayed()));

        return lines.toArray(new String[lines.size()]);
    }

    private int whoJustPlayed() {
        Optional<Player> opt = units.stream().filter(u -> u.did != null).map(u -> u.player).findFirst();
        Player p = opt.orElse(players.get(0));
        return p.index;
    }

    @Override
    protected String getGameName() {
        return "WondevWoman";
    }

    @Override
    protected String getHeadlineAtGameStartForConsole() {
        return null;
    }

    @Override
    protected int getMinimumPlayerCount() {
        return 2;
    }

    @Override
    protected boolean showTooltips() {
        return true;
    }

    @Override
    protected String[] getPlayerActions(int playerIdx, int round) {
        return new String[0];
    }

    @Override
    protected boolean isPlayerDead(int playerIdx) {
        if (GAME_VERSION == 0) {
            return players.get(playerIdx).dead;
        }
        return false;
    }

    @Override
    protected String getDeathReason(int playerIdx) {
        return "$" + playerIdx + ": Eliminated!";
    }

    @Override
    protected int getScore(int playerIdx) {
        Player p = players.get(playerIdx);
        if (WIN_ON_MAX_HEIGHT) {
            if (p.dead)
                return -1;
            if (p.won)
                return 1;
            return 0;
        }
        return p.score;
    }

    @Override
    protected String[] getGameSummary(int round) {
        List<String> lines = new ArrayList<>();
        for (Unit u : units) {
            if (u.moved()) {
                if (u.did.moveValid) {
                    lines.add(translate("MoveValid", u.player.index, u.index, u.did.moveTarget.x, u.did.moveTarget.y));
                } else {
                    printError("impossible");
                }
                if (u.did.placeValid) {
                    lines.add(translate("PlaceValid", u.did.placeTarget.x, u.did.placeTarget.y));
                } else {
                    lines.add(translate("CancelledPlace", u.did.placeTarget.x, u.did.placeTarget.y));
                }
                if (u.did.scorePoint) {
                    if (WIN_ON_MAX_HEIGHT) {
                        lines.add(translate("Wins"));
                    } else {
                        lines.add(translate("Scores"));
                    }

                }
            } else if (u.pushed()) {
                if (u.did.moveValid && u.did.placeValid) {
                    lines.add(translate("PushToPlaceOn", u.player.index, u.index, u.did.moveTarget.x, u.did.moveTarget.y, u.did.placeTarget.x,
                            u.did.placeTarget.y));
                } else if (!u.did.moveValid && !u.did.placeValid) {
                    lines.add(translate("CancelledPush", u.player.index, u.index, u.did.placeTarget.x, u.did.placeTarget.y));
                } else {
                    printError("also impossible");
                }
            }

        }
        return lines.toArray(new String[lines.size()]);
    }

    @Override
    protected void setPlayerTimeout(int frame, int round, int playerIdx) {
        players.get(playerIdx).die(round);
    }

    @Override
    protected int getMaxRoundCount(int playerCount) {
        return 200;
    }

}

// ------------------------------------------------------------------------------------------------------------

abstract class MultiReferee extends AbstractReferee {
    private Properties properties;

    public MultiReferee(InputStream is, PrintStream out, PrintStream err) throws IOException {
        super(is, out, err);
    }

    @Override
    protected final void handleInitInputForReferee(int playerCount, String[] init) throws InvalidFormatException {
        properties = new Properties();
        try {
            for (String s : init) {
                properties.load(new StringReader(s));
            }
        } catch (IOException e) {
        }
        initReferee(playerCount, properties);
        properties = getConfiguration();
    }

    abstract protected void initReferee(int playerCount, Properties prop) throws InvalidFormatException;

    abstract protected Properties getConfiguration();

    protected void appendDataToEnd(PrintStream stream) throws IOException {
        stream.println(OutputCommand.UINPUT.format(properties.size()));
        for (Map.Entry<Object, Object> t : properties.entrySet()) {
            stream.println(t.getKey() + "=" + t.getValue());
        }
    }
}

abstract class AbstractReferee {
    private static final Pattern HEADER_PATTERN = Pattern.compile("\\[\\[(?<cmd>.+)\\] ?(?<lineCount>[0-9]+)\\]");
    private static final String LOST_PARSING_REASON_CODE = "INPUT";
    private static final String LOST_PARSING_REASON = "Failure: invalid input";

    protected static class PlayerStatus {
        private int id;
        private int score;
        private boolean lost, win;
        private String info;
        private String reasonCode;
        private String[] nextInput;

        public PlayerStatus(int id) {
            this.id = id;
            lost = false;
            info = null;
        }

        public int getScore() {
            return score;
        }

        public boolean isLost() {
            return lost;
        }

        public String getInfo() {
            return info;
        }

        public int getId() {
            return id;
        }

        public String getReasonCode() {
            return reasonCode;
        }

        public String[] getNextInput() {
            return nextInput;
        }
    }

    private Properties messages = new Properties();

    @SuppressWarnings("serial")
    final class InvalidFormatException extends Exception {
        public InvalidFormatException(String message) {
            super(message);
        }
    }

    @SuppressWarnings("serial")
    abstract class GameException extends Exception {
        private String reasonCode, tooltipCode;
        private Object[] values;

        public GameException(String reasonCode, Object... values) {
            this.reasonCode = reasonCode;
            this.values = values;
        }

        public void setTooltipCode(String tooltipCode) {
            this.tooltipCode = tooltipCode;
        }

        public String getReason() {
            if (reasonCode != null) {
                return translate(reasonCode, values);
            } else {
                return null;
            }
        }

        public String getReasonCode() {
            return reasonCode;
        }

        public String getTooltipCode() {
            if (tooltipCode != null) {
                return tooltipCode;
            }
            return getReasonCode();
        }
    }

    @SuppressWarnings("serial")
    class LostException extends GameException {
        public LostException(String reasonCode, Object... values) {
            super(reasonCode, values);
        }
    }

    @SuppressWarnings("serial")
    class WinException extends GameException {
        public WinException(String reasonCode, Object... values) {
            super(reasonCode, values);
        }
    }

    @SuppressWarnings("serial")
    class InvalidInputException extends GameException {
        public InvalidInputException(String expected, String found) {
            super("InvalidInput", expected, found);
        }
    }

    @SuppressWarnings("serial")
    class GameOverException extends GameException {
        public GameOverException(String reasonCode, Object... values) {
            super(reasonCode, values);
        }
    }

    @SuppressWarnings("serial")
    class GameErrorException extends Exception {
        public GameErrorException(Throwable cause) {
            super(cause);
        }
    }

    public static enum InputCommand {
        INIT, GET_GAME_INFO, SET_PLAYER_OUTPUT, SET_PLAYER_TIMEOUT
    }

    public static enum OutputCommand {
        VIEW, INFOS, NEXT_PLAYER_INPUT, NEXT_PLAYER_INFO, SCORES, UINPUT, TOOLTIP, SUMMARY;

        public String format(int lineCount) {
            return String.format("[[%s] %d]", this.name(), lineCount);
        }
    }

    @SuppressWarnings("serial")
    public static class OutputData extends LinkedList<String> {
        private OutputCommand command;

        public OutputData(OutputCommand command) {
            this.command = command;
        }

        public boolean add(String s) {
            if (s != null)
                return super.add(s);
            return false;
        }

        public void addAll(String[] data) {
            if (data != null)
                super.addAll(Arrays.asList(data));
        }

        @Override
        public String toString() {
            StringWriter writer = new StringWriter();
            PrintWriter out = new PrintWriter(writer);
            out.println(this.command.format(this.size()));
            for (String line : this) {
                out.println(line);
            }
            return writer.toString().trim();
        }
    }

    private static class Tooltip {
        int player;
        String message;

        public Tooltip(int player, String message) {
            this.player = player;
            this.message = message;
        }
    }

    private Set<Tooltip> tooltips;
    private int playerCount, alivePlayerCount;
    private int currentPlayer, nextPlayer;
    private PlayerStatus lastPlayer, playerStatus;
    private int frame, round;
    private PlayerStatus[] players;
    private String[] initLines;
    private boolean newRound;
    private String reasonCode, reason;

    private InputStream is;
    private PrintStream out;
    private PrintStream err;

    public AbstractReferee(InputStream is, PrintStream out, PrintStream err) throws IOException {
        tooltips = new HashSet<>();
        this.is = is;
        this.out = out;
        this.err = err;
        start();
    }

    @SuppressWarnings("resource")
    public void start() throws IOException {
        try {
            handleInitInputForReferee(2, new String[0]);
        } catch (InvalidFormatException e) {
            return;
        }

        Scanner s = new Scanner(is);

        try {
            // Read ###Start 2
            s.nextLine();
            playerCount = alivePlayerCount = 2;
            players = new PlayerStatus[2];
            players[0] = new PlayerStatus(0);
            players[1] = new PlayerStatus(1);
            playerStatus = players[0];
            currentPlayer = nextPlayer = 1;
            round = -1;
            newRound = true;

            while (true) {
                lastPlayer = playerStatus;
                playerStatus = nextPlayer();

                if (this.round >= getMaxRoundCount(this.playerCount)) {
                    throw new GameOverException("maxRoundsCountReached");
                }

                if (newRound) {
                    prepare(round);
                    if (!this.isTurnBasedGame()) {
                        for (PlayerStatus player : this.players) {
                            if (!player.lost) {
                                player.nextInput = getInputForPlayer(round, player.id);
                            } else {
                                player.nextInput = null;
                            }
                        }
                    }
                }

                out.println("###Input " + nextPlayer);
                if (this.round == 0) {
                    for (String line : getInitInputForPlayer(nextPlayer)) {
                        out.println(line);
                    }
                }

                if (this.isTurnBasedGame()) {
                    for (String line : getInputForPlayer(round, nextPlayer)) {
                        out.println(line);
                    }
                } else {
                    for (String line : this.players[nextPlayer].nextInput) {
                        out.println(line);
                    }
                }

                int expectedOutputLineCount = getExpectedOutputLineCountForPlayer(nextPlayer);
                out.println("###Output " + nextPlayer + " " + expectedOutputLineCount);
                try {
                    String[] outputs = new String[expectedOutputLineCount];
                    for (int i = 0; i < expectedOutputLineCount; i++) {
                        outputs[i] = s.nextLine();
                    }
                    handlePlayerOutput(0, round, nextPlayer, outputs);
                } catch (WinException e) {
                    playerStatus.score = getScore(nextPlayer);
                    playerStatus.win = true;
                    playerStatus.info = e.getReason();
                    playerStatus.reasonCode = e.getReasonCode();
                    lastPlayer = playerStatus;
                    throw new GameOverException(null);
                } catch (LostException | InvalidInputException e) {
                    playerStatus.score = getScore(nextPlayer);
                    playerStatus.lost = true;
                    playerStatus.info = e.getReason();
                    playerStatus.reasonCode = e.getReasonCode();
					boolean otherPlayerIsDead = lastPlayer.lost;
					lastPlayer = playerStatus;
					//only end the game, if both players are dead
					if (otherPlayerIsDead)
						throw new GameOverException(null);
                }
            }
        } catch (GameOverException e) {
            newRound = true;
            reasonCode = e.getReasonCode();
            reason = e.getReason();
            err.println(reason);
            prepare(round);
            updateScores();
            if (players[0].score > players[1].score) {
                out.println("###End 0 1");
            } else if (players[0].score < players[1].score) {
                out.println("###End 1 0");
            } else {
                out.println("###End 01");
            }
        } finally {
            s.close();
        }
    }

    private PlayerStatus nextPlayer() throws GameOverException {
        currentPlayer = nextPlayer;
        newRound = false;
        do {
            ++nextPlayer;
            if (nextPlayer >= playerCount) {
                nextRound();
                nextPlayer = 0;
            }
        } while (this.players[nextPlayer].lost || this.players[nextPlayer].win);
        return players[nextPlayer];
    }

    protected String getColoredReason(boolean error, String reason) {
        if (error) {
            return String.format("¤RED¤%s§RED§", reason);
        } else {
            return String.format("¤GREEN¤%s§GREEN§", reason);
        }
    }

    private void dumpView() {
        OutputData data = new OutputData(OutputCommand.VIEW);
        String reasonCode = this.reasonCode;
        if (reasonCode == null && playerStatus != null)
            reasonCode = playerStatus.reasonCode;

        if (newRound) {
            if (reasonCode != null) {
                data.add(String.format("KEY_FRAME %d %s", this.frame, reasonCode));
            } else {
                data.add(String.format("KEY_FRAME %d", this.frame));
            }
            if (frame == 0) {
                data.add(getGameName());
                data.addAll(getInitDataForView());
            }
        } else {
            if (reasonCode != null) {
                data.add(String.format("INTERMEDIATE_FRAME %d %s", this.frame, reasonCode));
            } else {
                data.add(String.format("INTERMEDIATE_FRAME %d", frame));
            }
        }
        if (newRound || isTurnBasedGame()) {
            data.addAll(getFrameDataForView(round, frame, newRound));
        }

        out.println(data);
    }

    private void dumpInfos() {
        OutputData data = new OutputData(OutputCommand.INFOS);
        if (reason != null && isTurnBasedGame()) {
            data.add(getColoredReason(true, reason));
        } else {
            if (lastPlayer != null) {
                String head = lastPlayer.info;
                if (head != null) {
                    data.add(getColoredReason(lastPlayer.lost, head));
                } else {
                    if (frame > 0) {
                        data.addAll(getPlayerActions(this.currentPlayer, newRound ? this.round - 1 : this.round));
                    }
                }
            }
        }
        out.println(data);
        if (newRound && round >= -1 && playerCount > 1) {
            OutputData summary = new OutputData(OutputCommand.SUMMARY);
            if (frame == 0) {
                String head = getHeadlineAtGameStartForConsole();
                if (head != null) {
                    summary.add(head);
                }
            }
            if (round >= 0) {
                summary.addAll(getGameSummary(round));
            }
            if (!isTurnBasedGame() && reason != null) {
                summary.add(getColoredReason(true, reason));
            }
            out.println(summary);
        }

        if (!tooltips.isEmpty() && (newRound || isTurnBasedGame())) {
            data = new OutputData(OutputCommand.TOOLTIP);
            for (Tooltip t : tooltips) {
                data.add(t.message);
                data.add(String.valueOf(t.player));
            }
            tooltips.clear();
            out.println(data);
        }
    }

    private void dumpNextPlayerInfos() {
        OutputData data = new OutputData(OutputCommand.NEXT_PLAYER_INFO);
        data.add(String.valueOf(nextPlayer));
        data.add(String.valueOf(getExpectedOutputLineCountForPlayer(nextPlayer)));
        if (this.round == 0) {
            data.add(String.valueOf(getMillisTimeForFirstRound()));
        } else {
            data.add(String.valueOf(getMillisTimeForRound()));
        }
        out.println(data);
    }

    private void dumpNextPlayerInput() {
        OutputData data = new OutputData(OutputCommand.NEXT_PLAYER_INPUT);
        if (this.round == 0) {
            data.addAll(getInitInputForPlayer(nextPlayer));
        }
        if (this.isTurnBasedGame()) {
            this.players[nextPlayer].nextInput = getInputForPlayer(round, nextPlayer);
        }
        data.addAll(this.players[nextPlayer].nextInput);
        out.println(data);
    }

    protected final String translate(String code, Object... values) {
        try {
            return String.format((String) messages.get(code), values);
        } catch (NullPointerException e) {
            return code;
        }
    }

    protected final void printError(Object message) {
        err.println(message);
    }

    protected int getMillisTimeForFirstRound() {
        return 1000;
    }

    protected int getMillisTimeForRound() {
        return 150;
    }

    protected int getMaxRoundCount(int playerCount) {
        return 400;
    }

    private void nextRound() throws GameOverException {
        newRound = true;
        if (++round > 0) {
            updateGame(round);
        }
        if (gameOver()) {
            throw new GameOverException(null);
        }
    }

    protected boolean gameIsOver() {
        return this.gameOver();
    }

    protected boolean gameOver() {
        return alivePlayerCount < getMinimumPlayerCount();
    }

    private void updateScores() {
        for (int i = 0; i < playerCount; ++i) {
            if (!players[i].lost && isPlayerDead(i)) {
                alivePlayerCount--;
                players[i].lost = true;
                players[i].info = getDeathReason(i);
                addToolTip(i, players[i].info);
            }
            players[i].score = getScore(i);
        }
    }

    protected void addToolTip(int player, String message) {
        if (showTooltips())
            tooltips.add(new Tooltip(player, message));
    }

    /**
     * Add message (key = reasonCode, value = reason)
     *
     * @param p
     */
    protected abstract void populateMessages(Properties p);

    protected boolean isTurnBasedGame() {
        return false;
    }

    protected abstract void handleInitInputForReferee(int playerCount, String[] init) throws InvalidFormatException;

    protected abstract String[] getInitDataForView();

    protected abstract String[] getFrameDataForView(int round, int frame, boolean keyFrame);

    protected abstract int getExpectedOutputLineCountForPlayer(int playerIdx);

    protected abstract String getGameName();

    protected abstract void appendDataToEnd(PrintStream stream) throws IOException;

    protected abstract void handlePlayerOutput(int frame, int round, int playerIdx, String[] output) throws WinException, LostException, InvalidInputException;

    protected abstract String[] getInitInputForPlayer(int playerIdx);

    protected abstract String[] getInputForPlayer(int round, int playerIdx);

    protected abstract String getHeadlineAtGameStartForConsole();

    protected abstract int getMinimumPlayerCount();

    protected abstract boolean showTooltips();

    /**
     * @param round
     * @return scores of all players
     * @throws GameOverException
     */
    protected abstract void updateGame(int round) throws GameOverException;

    protected abstract void prepare(int round);

    protected abstract boolean isPlayerDead(int playerIdx);

    protected abstract String getDeathReason(int playerIdx);

    protected abstract int getScore(int playerIdx);

    protected abstract String[] getGameSummary(int round);

    protected abstract String[] getPlayerActions(int playerIdx, int round);

    protected abstract void setPlayerTimeout(int frame, int round, int playerIdx);
}
