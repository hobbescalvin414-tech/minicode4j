import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import javax.swing.*;

/**
 * 终端风格贪吃蛇小游戏 - 增强版
 * 控制: W/↑ 上, S/↓ 下, A/← 左, D/→ 右, Space 暂停, R 重新开始, ESC 退出
 * 新功能: 多种食物、障碍物、道具、特效、关卡系统、连击系统、生命系统
 * 体验优化: 持久化最高分、开始界面、暂停覆盖层、颜色主题、轨迹效果、
 *          食物动画、屏幕震动、连击闪光、成就系统、游戏时间、食物计数
 */
public class SnakeGame extends JPanel implements ActionListener {

    // ── 地图与格子 ──────────────────────────────────────────
    private static final int TILE  = 20;   // 每格像素
    private static final int COLS  = 70;   // 列数
    private static final int ROWS  = 45;   // 行数
    private static final int WIDTH  = COLS * TILE;
    private static final int HEIGHT = ROWS * TILE;

    // ── 关卡配置 ──────────────────────────────────────────
    static class LevelConfig {
        final String name;           // 关卡名称
        final String description;    // 关卡描述
        final int foodTarget;        // 过关需要吃的数量
        final int baseSpeed;         // 基础速度(ms)
        final Color bgColor;         // 背景色
        final Color gridColor;       // 网格色
        final Color borderColor;     // 边框色
        final int[][] obstaclePattern; // 障碍物图案(null=随机)
        final int extraRandomObstacles; // 额外随机障碍物数
        final int goldenRate;        // 金色食物概率%
        final int specialRate;       // 特殊食物概率%
        final boolean wallBounce;    // 撞墙是否反弹(否则死亡)

        LevelConfig(String name, String desc, int foodTarget, int baseSpeed,
                    Color bg, Color grid, Color border,
                    int[][] pattern, int extraRandom, int goldenRate, int specialRate, boolean wallBounce) {
            this.name = name;
            this.description = desc;
            this.foodTarget = foodTarget;
            this.baseSpeed = baseSpeed;
            this.bgColor = bg;
            this.gridColor = grid;
            this.borderColor = border;
            this.obstaclePattern = pattern;
            this.extraRandomObstacles = extraRandom;
            this.goldenRate = goldenRate;
            this.specialRate = specialRate;
            this.wallBounce = wallBounce;
        }
    }

    // 关卡定义
    private static final LevelConfig[] LEVELS = {
        // 关卡1: 草原 - 新手教学
        new LevelConfig("草原", "轻松入门，熟悉操作", 8, 140,
            new Color(30, 30, 30), new Color(45, 45, 45), new Color(0, 100, 0),
            null, 3, 5, 5, false),

        // 关卡2: 沙漠 - 障碍初现
        new LevelConfig("沙漠", "小心仙人掌！", 10, 130,
            new Color(40, 35, 25), new Color(55, 50, 35), new Color(180, 150, 60),
            new int[][]{{5,5},{5,6},{5,7},{10,10},{10,11},{10,12},{15,3},{15,4},{15,5}},
            4, 8, 5, false),

        // 关卡3: 雪原 - 加速挑战
        new LevelConfig("雪原", "冰雪路面，速度更快", 10, 120,
            new Color(35, 40, 50), new Color(50, 55, 65), new Color(100, 150, 220),
            new int[][]{{8,4},{8,5},{8,6},{8,7},{8,8},{20,15},{21,15},{22,15},{23,15},{24,15}},
            5, 10, 8, false),

        // 关卡4: 森林 - 迷宫初探
        new LevelConfig("森林", "树木丛生，小心迷路", 12, 125,
            new Color(20, 35, 20), new Color(35, 50, 35), new Color(50, 120, 50),
            new int[][]{
                {5,2},{5,3},{5,4},{5,5},{5,6},{5,7},{5,8},
                {15,22},{16,22},{17,22},{18,22},{19,22},{20,22},{21,22},
                {30,8},{30,9},{30,10},{30,11},{30,12},{30,13},{30,14},
                {40,18},{41,18},{42,18},{43,18},{44,18}
            },
            6, 10, 10, false),

        // 关卡5: 沼泽 - 诡异地形
        new LevelConfig("沼泽", "危机四伏的湿地", 12, 120,
            new Color(25, 35, 30), new Color(40, 50, 45), new Color(80, 140, 100),
            new int[][]{
                {10,5},{11,5},{12,5},{13,5},{14,5},
                {10,25},{11,25},{12,25},{13,25},{14,25},
                {25,10},{25,11},{25,12},{25,13},{25,14},
                {25,16},{25,17},{25,18},{25,19},{25,20},
                {35,3},{36,3},{37,3},{38,3},
                {35,27},{36,27},{37,27},{38,27}
            },
            8, 15, 10, false),

        // 关卡6: 火山 - 烈焰试炼
        new LevelConfig("火山", "岩浆遍布，极速挑战", 12, 110,
            new Color(45, 25, 20), new Color(60, 35, 30), new Color(200, 80, 30),
            new int[][]{
                {0,14},{1,14},{2,14},{3,14},{4,14},{5,14},{6,14},
                {15,0},{15,1},{15,2},{15,3},{15,4},{15,5},
                {15,25},{15,26},{15,27},{15,28},{15,29},
                {25,14},{26,14},{27,14},{28,14},{29,14},
                {35,0},{35,1},{35,2},{35,3},{35,4},
                {35,25},{35,26},{35,27},{35,28},{35,29},
                {44,14},{45,14},{46,14},{47,14},{48,14},{49,14}
            },
            10, 15, 12, false),

        // 关卡7: 海洋 - 深海探秘
        new LevelConfig("海洋", "深海之中暗藏杀机", 14, 115,
            new Color(15, 25, 45), new Color(25, 40, 60), new Color(30, 100, 180),
            new int[][]{
                {8,4},{8,5},{8,6},{8,7},{8,8},{8,9},{8,10},{8,11},{8,12},
                {20,18},{21,18},{22,18},{23,18},{24,18},{25,18},{26,18},{27,18},{28,18},
                {35,4},{35,5},{35,6},{35,7},{35,8},{35,9},{35,10},{35,11},{35,12},
                {42,18},{42,19},{42,20},{42,21},{42,22},{42,23},{42,24},{42,25},{42,26}
            },
            8, 18, 15, false),

        // 关卡8: 天空 - 云端飞行
        new LevelConfig("天空", "高空作业，手速为王", 14, 105,
            new Color(30, 35, 55), new Color(45, 50, 70), new Color(150, 180, 255),
            new int[][]{
                {10,0},{10,1},{10,2},{10,3},{10,4},{10,5},{10,25},{10,26},{10,27},{10,28},{10,29},
                {25,10},{25,11},{25,12},{25,13},{25,14},{25,15},{25,16},{25,17},{25,18},{25,19},{25,20},
                {40,0},{40,1},{40,2},{40,3},{40,4},{40,5},{40,25},{40,26},{40,27},{40,28},{40,29}
            },
            10, 20, 15, false),

        // 关卡9: 太空 - 失重领域
        new LevelConfig("太空", "失重环境，可以穿墙", 15, 110,
            new Color(10, 10, 25), new Color(20, 20, 40), new Color(100, 100, 200),
            new int[][]{
                {5,5},{5,6},{6,5},{6,6},
                {5,24},{5,23},{6,24},{6,23},
                {24,14},{24,15},{25,14},{25,15},{26,14},{26,15},
                {44,5},{44,6},{45,5},{45,6},
                {44,24},{44,23},{45,24},{45,23}
            },
            12, 20, 20, true),  // 太空关可以穿墙！

        // 关卡10: 地狱 - 终极挑战
        new LevelConfig("地狱", "终极挑战，你准备好了吗？", 20, 100,
            new Color(35, 15, 15), new Color(50, 25, 25), new Color(180, 30, 30),
            new int[][]{
                // 中央十字
                {24,0},{24,1},{24,2},{24,3},{24,4},{24,5},{24,6},{24,7},{24,8},
                {24,22},{24,23},{24,24},{24,25},{24,26},{24,27},{24,28},{24,29},
                {0,14},{1,14},{2,14},{3,14},{4,14},{5,14},{6,14},
                {43,14},{44,14},{45,14},{46,14},{47,14},{48,14},{49,14},
                // 四角方块
                {6,6},{6,7},{7,6},{7,7},
                {6,23},{6,22},{7,23},{7,22},
                {43,6},{43,7},{42,6},{42,7},
                {43,23},{43,22},{42,23},{42,22}
            },
            15, 15, 20, false)
    };

    // ── 游戏状态枚举 ────────────────────────────────────────
    enum GameState {
        MENU,       // 开始菜单
        PLAYING,    // 游戏进行中
        PAUSED,     // 暂停
        GAME_OVER,  // 游戏结束
        LEVEL_CLEAR // 过关动画
    }

    // ── 蛇颜色主题枚举 ──────────────────────────────────────
    enum SnakeColorTheme {
        GREEN(new Color(0, 220, 80), new Color(0, 180, 60), "经典绿"),
        BLUE(new Color(0, 150, 255), new Color(0, 120, 200), "海洋蓝"),
        RED(new Color(220, 50, 50), new Color(180, 40, 40), "热情红"),
        PURPLE(new Color(180, 0, 255), new Color(140, 0, 200), "神秘紫");

        final Color headColor;
        final Color bodyColor;
        final String name;

        SnakeColorTheme(Color headColor, Color bodyColor, String name) {
            this.headColor = headColor;
            this.bodyColor = bodyColor;
            this.name = name;
        }
    }

    // ── 游戏状态 ────────────────────────────────────────────
    private final Deque<Point> snake = new ArrayDeque<>();
    private Point food;
    private FoodType foodType = FoodType.NORMAL;
    private int dx, dy;          // 当前方向
    private int nextDx, nextDy;  // 下一帧方向(防180°掉头)
    private boolean running;
    private boolean paused;
    private boolean gameOver;
    private int score;
    private int highScore;  // 最高分记录
    private final Timer timer;
    private final Random rand = new Random();

    // 新增游戏状态
    private GameState gameState = GameState.MENU;
    private SnakeColorTheme colorTheme = SnakeColorTheme.GREEN;
    private int lives = 3;          // 生命数
    private int level = 1;          // 当前关卡
    private int foodEaten = 0;      // 当前关卡已吃食物数
    private int comboCount = 0;     // 连击数
    private long lastEatTime = 0;   // 上次吃食物时间
    private static final long COMBO_TIMEOUT = 2000; // 连击超时(毫秒)
    private int totalFoodEaten = 0; // 总共吃食物数
    private long gameStartTime = 0; // 游戏开始时间
    private long totalGameTime = 0; // 总游戏时间（毫秒）

    // 障碍物系统
    private List<Point> obstacles = new ArrayList<>();
    private int obstacleCount = 5;  // 初始障碍物数量

    // 道具系统
    private List<PowerUp> powerUps = new ArrayList<>();
    private boolean hasShield = false;     // 护盾
    private boolean canPassWall = false;   // 穿墙
    private boolean hasMagnet = false;     // 磁铁
    private long powerUpExpireTime = 0;    // 道具效果过期时间
    private static final long POWER_UP_DURATION = 10000; // 道具持续时间（10秒）

    // 特效系统
    private List<Particle> particles = new ArrayList<>();
    private List<Animation> animations = new ArrayList<>();

    // 最高分持久化
    private static final String HIGHSCORE_FILE = "snake_highscore.properties";

    // 轨迹系统
    private List<TrailPoint> trail = new ArrayList<>();
    private static final int TRAIL_LENGTH = 5;

    // 食物动画
    private FoodAnimation foodAnimation = null;

    // 屏幕震动
    private ScreenShake screenShake = null;

    // 连击特效
    private int comboFlashIntensity = 0;
    private long lastComboFlashTime = 0;

    // 成就系统
    private List<Achievement> achievements = new ArrayList<>();
    private List<Achievement> unlockedAchievements = new ArrayList<>();

    // 食物动画
    private int foodAnimFrame = 0;
    private long lastFoodAnimTime = 0;
    private static final long FOOD_ANIM_INTERVAL = 500; // 食物动画间隔

    // 过关动画
    private long levelClearStartTime = 0;
    private static final long LEVEL_CLEAR_DURATION = 3000; // 3秒
    private int levelClearAnimFrame = 0;
    private List<Particle> levelClearParticles = new ArrayList<>();

    // 当前关卡配置
    private LevelConfig currentLevelConfig = LEVELS[0];
    private int maxLevel = 0; // 最高通关记录

    // 怪物系统
    private List<Monster> monsters = new ArrayList<>();
    private int monstersKilled = 0;           // 本关击杀数
    private int totalMonstersKilled = 0;      // 总击杀数
    private int consecutiveHits = 0;          // 连续命中数
    private int consecutiveMisses = 0;        // 连续未命中数
    private boolean hitByMonsterThisLevel = false; // 本关是否被怪物碰到

    // 子弹系统
    private List<Bullet> bullets = new ArrayList<>();
    private static final int MAX_BULLETS = 5;
    private static final double BULLET_SPEED = 30.0;  // 每帧像素（极速子弹）
    private static final long BULLET_LIFETIME = 6000; // 6秒后消失（超远射程）

    // 控制反转效果
    private boolean controlReversed = false;
    private long controlReversedEndTime = 0;

    // ── 颜色 ────────────────────────────────────────────────
    private static final Color BG        = new Color(30, 30, 30);
    private static final Color GRID      = new Color(45, 45, 45);
    private static final Color SNAKE_HEAD= new Color(0, 220, 80);
    private static final Color SNAKE_BODY= new Color(0, 180, 60);
    private static final Color FOOD_COLOR= new Color(220, 50, 50);
    private static final Color TEXT_COLOR = new Color(220, 220, 220);

    // 新增颜色
    private static final Color GOLDEN_FOOD = new Color(255, 215, 0);
    private static final Color BLUE_FOOD = new Color(0, 150, 255);
    private static final Color PURPLE_FOOD = new Color(180, 0, 255);
    private static final Color GREEN_FOOD = new Color(0, 255, 100);
    private static final Color OBSTACLE_COLOR = new Color(100, 100, 100);
    private static final Color SHIELD_COLOR = new Color(255, 255, 0);
    private static final Color WALL_PASS_COLOR = new Color(200, 200, 200);
    private static final Color MAGNET_COLOR = new Color(255, 100, 200);

    // 怪物和子弹颜色
    private static final Color MONSTER_WANDERER = new Color(0, 200, 0);
    private static final Color MONSTER_TRACKER = new Color(200, 0, 0);
    private static final Color BULLET_COLOR = new Color(255, 255, 0);
    private static final Color BULLET_TRAIL = new Color(255, 200, 0, 100);

    // ── 内部类 ──────────────────────────────────────────────
    enum FoodType {
        NORMAL(10, 1),      // 普通食物：+10分，蛇长+1
        GOLDEN(50, 1),      // 金色食物：+50分，蛇长+1
        BLUE(10, 1),        // 蓝色食物：+10分，蛇长+1，减速
        PURPLE(10, 1),      // 紫色食物：+10分，蛇长+1，加速
        GREEN(20, 3);       // 绿色食物：+20分，蛇长+3

        final int score;
        final int growth;

        FoodType(int score, int growth) {
            this.score = score;
            this.growth = growth;
        }
    }

    class PowerUp {
        Point position;
        PowerUpType type;
        long spawnTime;

        PowerUp(Point position, PowerUpType type) {
            this.position = position;
            this.type = type;
            this.spawnTime = System.currentTimeMillis();
        }
    }

    enum PowerUpType {
        SHIELD,      // 护盾
        WALL_PASS,   // 穿墙
        MAGNET       // 磁铁
    }

    class Particle {
        double x, y, dx, dy;
        Color color;
        int life;
        int maxLife;

        Particle(double x, double y, Color color) {
            this.x = x;
            this.y = y;
            this.color = color;
            this.dx = (rand.nextDouble() - 0.5) * 2;
            this.dy = (rand.nextDouble() - 0.5) * 2;
            this.life = 30;
            this.maxLife = 30;
        }

        void update() {
            x += dx;
            y += dy;
            life--;
        }

        boolean isDead() {
            return life <= 0;
        }

        void draw(Graphics2D g) {
            float alpha = (float) life / maxLife;
            Color c = new Color(color.getRed()/255f, color.getGreen()/255f,
                              color.getBlue()/255f, alpha);
            g.setColor(c);
            g.fillOval((int)x, (int)y, 4, 4);
        }
    }

    class Animation {
        double x, y;
        int frame;
        int maxFrames;
        String text;
        Color color;

        Animation(double x, double y, String text, Color color) {
            this.x = x;
            this.y = y;
            this.text = text;
            this.color = color;
            this.frame = 0;
            this.maxFrames = 60;
        }

        void update() {
            y -= 0.5; // 向上飘动
            frame++;
        }

        boolean isDead() {
            return frame >= maxFrames;
        }

        void draw(Graphics2D g) {
            float alpha = 1.0f - (float) frame / maxFrames;
            Color c = new Color(color.getRed()/255f, color.getGreen()/255f,
                              color.getBlue()/255f, alpha);
            g.setColor(c);
            g.setFont(new Font("Microsoft YaHei", Font.BOLD, 16));
            g.drawString(text, (int)x, (int)y);
        }
    }

    class TrailPoint {
        int x, y;
        int alpha; // 透明度 0-255

        TrailPoint(int x, int y, int alpha) {
            this.x = x;
            this.y = y;
            this.alpha = alpha;
        }
    }

    class FoodAnimation {
        double scale; // 0.0 to 1.0
        long startTime;
        static final long DURATION = 300; // 300ms

        FoodAnimation() {
            this.scale = 0.0;
            this.startTime = System.currentTimeMillis();
        }

        void update() {
            long elapsed = System.currentTimeMillis() - startTime;
            double progress = Math.min(1.0, (double) elapsed / DURATION);
            // easeOut效果
            scale = 1.0 - Math.pow(1.0 - progress, 3);
        }

        boolean isComplete() {
            return scale >= 1.0;
        }
    }

    class ScreenShake {
        int intensity; // 震动强度（像素）
        long duration; // 持续时间（毫秒）
        long startTime;
        int offsetX, offsetY;

        ScreenShake(int intensity, long duration) {
            this.intensity = intensity;
            this.duration = duration;
            this.startTime = System.currentTimeMillis();
            this.offsetX = 0;
            this.offsetY = 0;
        }

        void update() {
            long elapsed = System.currentTimeMillis() - startTime;
            if (elapsed >= duration) {
                offsetX = 0;
                offsetY = 0;
                return;
            }
            double remaining = 1.0 - (double) elapsed / duration;
            int currentIntensity = (int) (intensity * remaining);
            offsetX = (int) ((Math.random() - 0.5) * 2 * currentIntensity);
            offsetY = (int) ((Math.random() - 0.5) * 2 * currentIntensity);
        }

        boolean isActive() {
            return System.currentTimeMillis() - startTime < duration;
        }
    }

    class Achievement {
        String id;
        String name;
        String description;
        boolean unlocked;

        Achievement(String id, String name, String description) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.unlocked = false;
        }
    }

    // ── 怪物系统 ──────────────────────────────────────────
    enum MonsterType {
        WANDERER(1, 50, new Color(0, 200, 0)),   // 游荡怪：1血，50分，绿色
        TRACKER(2, 100, new Color(200, 0, 0));    // 追踪怪：2血，100分，红色

        final int health;
        final int score;
        final Color color;

        MonsterType(int health, int score, Color color) {
            this.health = health;
            this.score = score;
            this.color = color;
        }
    }

    class Monster {
        Point position;
        int dx, dy;
        MonsterType type;
        int health;
        long lastMoveTime;
        boolean alive;

        Monster(Point position, MonsterType type) {
            this.position = position;
            this.type = type;
            this.health = type.health;
            this.alive = true;
            this.lastMoveTime = System.currentTimeMillis();
            // 初始随机方向
            int[][] dirs = {{0,1},{0,-1},{1,0},{-1,0}};
            int[] dir = dirs[rand.nextInt(4)];
            this.dx = dir[0];
            this.dy = dir[1];
        }
    }

    class Bullet {
        double x, y;           // 浮点位置（像素坐标）
        double dx, dy;         // 飞行方向（归一化）
        boolean alive;
        long createTime;
        List<Point> trail;     // 尾迹

        Bullet(double x, double y, double dx, double dy) {
            this.x = x;
            this.y = y;
            this.dx = dx;
            this.dy = dy;
            this.alive = true;
            this.createTime = System.currentTimeMillis();
            this.trail = new ArrayList<>();
        }
    }

    public SnakeGame() {
        setPreferredSize(new Dimension(WIDTH, HEIGHT + 80)); // 底部留空显示分数和状态
        setBackground(BG);
        setFocusable(true);

        // 初始化成就系统
        initAchievements();

        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                int key = e.getKeyCode();

                // 菜单状态处理
                if (gameState == GameState.MENU) {
                    handleMenuInput(key);
                    return;
                }

                // 过关动画中按任意键继续
                if (gameState == GameState.LEVEL_CLEAR) {
                    advanceToNextLevel();
                    return;
                }

                if (gameOver) {
                    if (key == KeyEvent.VK_R) {
                        gameState = GameState.PLAYING;
                        initGame();
                    }
                    else if (key == KeyEvent.VK_ESCAPE) {
                        gameState = GameState.MENU;
                        repaint();
                    }
                    return;
                }
                if (key == KeyEvent.VK_SPACE)  {
                    paused = !paused;
                    gameState = paused ? GameState.PAUSED : GameState.PLAYING;
                    if (!paused) {
                        // 恢复计时
                        gameStartTime = System.currentTimeMillis() - totalGameTime;
                    } else {
                        // 暂停计时
                        totalGameTime = System.currentTimeMillis() - gameStartTime;
                    }
                    return;
                }
                if (key == KeyEvent.VK_ESCAPE) {
                    gameState = GameState.MENU;
                    repaint();
                    return;
                }
                if (key == KeyEvent.VK_P) {
                    // 截图功能
                    takeScreenshot();
                    return;
                }

                // 方向键或WASD（禁止180度掉头）
                switch (key) {
                    case KeyEvent.VK_W, KeyEvent.VK_UP    -> { if (dy != 1)  { nextDx =  0; nextDy = -1; } }
                    case KeyEvent.VK_S, KeyEvent.VK_DOWN  -> { if (dy != -1) { nextDx =  0; nextDy =  1; } }
                    case KeyEvent.VK_A, KeyEvent.VK_LEFT  -> { if (dx != 1)  { nextDx = -1; nextDy =  0; } }
                    case KeyEvent.VK_D, KeyEvent.VK_RIGHT -> { if (dx != -1) { nextDx =  1; nextDy =  0; } }
                }
            }
        });

        // 鼠标射击
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                // 使用 mousePressed 代替 mouseClicked，响应更灵敏
                if (gameState == GameState.PLAYING && !paused && !gameOver) {
                    shootBullet(e.getX(), e.getY());
                }
            }
        });

        timer = new Timer(120, this); // ~8 fps
        loadHighScore();
    }

    private void shootBullet(int mouseX, int mouseY) {
        if (bullets.size() >= MAX_BULLETS) {
            // 移除最旧的子弹
            bullets.remove(0);
        }

        Point head = snake.peekLast(); // 使用 peekLast 获取蛇头
        if (head == null) return; // 安全检查
        double startX = head.x * TILE + TILE / 2.0;
        double startY = head.y * TILE + TILE / 2.0;

        // 计算方向向量
        double dx = mouseX - startX;
        double dy = mouseY - startY;
        double length = Math.sqrt(dx * dx + dy * dy);
        if (length > 0) {
            dx /= length;
            dy /= length;
        }

        bullets.add(new Bullet(startX, startY, dx, dy));
    }

    private void initAchievements() {
        achievements.add(new Achievement("first_food", "初次尝试", "吃到第一个食物"));
        achievements.add(new Achievement("score_100", "小有成就", "达到100分"));
        achievements.add(new Achievement("score_500", "游戏高手", "达到500分"));
        achievements.add(new Achievement("score_1000", "传奇玩家", "达到1000分"));
        achievements.add(new Achievement("combo_5", "连击达人", "连击达到5次"));
        achievements.add(new Achievement("combo_10", "连击大师", "连击达到10次"));
        achievements.add(new Achievement("level_5", "闯关专家", "达到第5关"));
        achievements.add(new Achievement("level_10", "闯关大师", "达到第10关"));
        achievements.add(new Achievement("food_50", "美食家", "总共吃50个食物"));
        achievements.add(new Achievement("food_100", "大胃王", "总共吃100个食物"));
        achievements.add(new Achievement("time_5min", "持久战", "游戏时间超过5分钟"));
        achievements.add(new Achievement("time_10min", "马拉松", "游戏时间超过10分钟"));
        achievements.add(new Achievement("monster_hunter", "怪物猎人", "击杀第一个怪物"));
        achievements.add(new Achievement("sharpshooter", "百发百中", "连续命中10个怪物"));
        achievements.add(new Achievement("untouchable", "毫发无伤", "一关内不被怪物碰到"));
    }

    private void handleMenuInput(int key) {
        // 数字键1-4选择颜色主题
        switch (key) {
            case KeyEvent.VK_1:
                colorTheme = SnakeColorTheme.GREEN;
                break;
            case KeyEvent.VK_2:
                colorTheme = SnakeColorTheme.BLUE;
                break;
            case KeyEvent.VK_3:
                colorTheme = SnakeColorTheme.RED;
                break;
            case KeyEvent.VK_4:
                colorTheme = SnakeColorTheme.PURPLE;
                break;
            default:
                // 其他任意键开始游戏
                gameState = GameState.PLAYING;
                initGame();
                break;
        }
        repaint();
    }

    private void initGame() {
        // 如果是菜单状态，不重置游戏
        if (gameState == GameState.MENU) {
            return;
        }

        snake.clear();
        // 蛇初始位置: 居中, 长度3, 向右
        int startX = COLS / 2, startY = ROWS / 2;
        for (int i = 2; i >= 0; i--) snake.add(new Point(startX - i, startY));

        dx = 1; dy = 0;
        nextDx = 1; nextDy = 0;
        score = 0;
        running = true;
        // highScore 保留，不重置
        paused = false;
        gameOver = false;
        gameState = GameState.PLAYING;

        // 重置新状态
        lives = 3;
        level = 1;
        foodEaten = 0;
        comboCount = 0;
        lastEatTime = 0;
        hasShield = false;
        canPassWall = false;
        hasMagnet = false;
        powerUpExpireTime = 0;
        totalFoodEaten = 0;
        gameStartTime = System.currentTimeMillis();
        totalGameTime = 0;

        // 清空列表
        obstacles.clear();
        powerUps.clear();
        particles.clear();
        animations.clear();
        trail.clear();
        foodAnimation = null;
        screenShake = null;
        comboFlashIntensity = 0;
        levelClearParticles.clear();

        // 应用关卡配置
        applyLevelConfig();

        spawnFood();
        spawnMonsters();
        timer.start();
    }

    // 应用当前关卡配置
    private void applyLevelConfig() {
        if (level - 1 < LEVELS.length) {
            currentLevelConfig = LEVELS[level - 1];
        } else {
            // 超出预设关卡，使用最后一关配置并增加难度
            currentLevelConfig = LEVELS[LEVELS.length - 1];
        }

        // 设置速度
        timer.setDelay(currentLevelConfig.baseSpeed);

        // 清空并生成障碍物
        obstacles.clear();
        if (currentLevelConfig.obstaclePattern != null) {
            for (int[] pos : currentLevelConfig.obstaclePattern) {
                Point p = new Point(pos[0], pos[1]);
                if (!snake.contains(p)) {
                    obstacles.add(p);
                }
            }
        }
        obstacleCount = obstacles.size();
        for (int i = 0; i < currentLevelConfig.extraRandomObstacles; i++) {
            spawnObstacle();
        }
        obstacleCount = obstacles.size();

        // 穿墙能力(太空关)
        canPassWall = currentLevelConfig.wallBounce;
    }

    // 进入下一关
    private void advanceToNextLevel() {
        level++;
        foodEaten = 0;

        // 蛇重置到中央
        snake.clear();
        int startX = COLS / 2, startY = ROWS / 2;
        for (int i = 2; i >= 0; i--) snake.add(new Point(startX - i, startY));
        dx = 1; dy = 0;
        nextDx = 1; nextDy = 0;

        // 清空道具和特效
        hasShield = false;
        canPassWall = false;
        hasMagnet = false;
        powerUpExpireTime = 0;
        powerUps.clear();
        particles.clear();
        animations.clear();
        trail.clear();
        foodAnimation = null;
        screenShake = null;
        levelClearParticles.clear();

        // 应用新关卡
        applyLevelConfig();

        // 记录最高关卡
        if (level > maxLevel) maxLevel = level;

        gameState = GameState.PLAYING;
        spawnFood();
        spawnMonsters();
    }

    private void spawnMonsters() {
        monsters.clear();
        int monsterCount = Math.min(5, 1 + level / 5);  // 每5关+1个，最多5个

        for (int i = 0; i < monsterCount; i++) {
            Point pos = getRandomEmptyPosition();
            if (pos != null) {
                // 追踪怪比例：关卡/20
                MonsterType type = rand.nextDouble() < level / 20.0 ?
                    MonsterType.TRACKER : MonsterType.WANDERER;
                monsters.add(new Monster(pos, type));
            }
        }
    }

    private Point getRandomEmptyPosition() {
        int attempts = 100;
        while (attempts-- > 0) {
            int x = rand.nextInt(COLS);
            int y = rand.nextInt(ROWS);
            Point p = new Point(x, y);
            if (!snake.contains(p) && !obstacles.contains(p) && !p.equals(food)) {
                // 确保不在蛇头附近（至少8格距离，适应大地图）
                Point head = snake.peekFirst();
                if (Math.abs(x - head.x) + Math.abs(y - head.y) > 8) {
                    return p;
                }
            }
        }
        return null;
    }

    private void spawnFood() {
        Point p;
        do {
            p = new Point(rand.nextInt(COLS), rand.nextInt(ROWS));
        } while (snake.contains(p) || obstacles.contains(p));

        food = p;

        // 根据关卡配置随机选择食物类型
        int type = rand.nextInt(100);
        int goldenRate = currentLevelConfig.goldenRate;
        int specialRate = currentLevelConfig.specialRate;

        if (type < (60 - goldenRate - specialRate)) { // 普通食物
            foodType = FoodType.NORMAL;
        } else if (type < (60 - specialRate + goldenRate)) { // 金色食物
            foodType = FoodType.GOLDEN;
        } else if (type < 70) { // 蓝色食物
            foodType = FoodType.BLUE;
        } else if (type < 80) { // 紫色食物
            foodType = FoodType.PURPLE;
        } else if (type < 80 + specialRate) { // 绿色食物
            foodType = FoodType.GREEN;
        } else {
            foodType = FoodType.NORMAL;
        }

        // 启动食物生成动画
        foodAnimation = new FoodAnimation();
    }

    private void spawnObstacle() {
        Point p;
        int attempts = 0;
        do {
            p = new Point(rand.nextInt(COLS), rand.nextInt(ROWS));
            attempts++;
        } while ((snake.contains(p) || obstacles.contains(p) ||
                 (food != null && p.equals(food))) && attempts < 100);

        if (attempts < 100) {
            obstacles.add(p);
        }
    }

    private void spawnPowerUp() {
        if (rand.nextInt(100) < 20) { // 20% 几率生成道具
            Point p;
            int attempts = 0;
            do {
                p = new Point(rand.nextInt(COLS), rand.nextInt(ROWS));
                attempts++;
            } while ((snake.contains(p) || obstacles.contains(p) ||
                     (food != null && p.equals(food)) ||
                     isPowerUpAt(p)) && attempts < 100);

            if (attempts < 100) {
                PowerUpType type;
                int t = rand.nextInt(3);
                if (t == 0) type = PowerUpType.SHIELD;
                else if (t == 1) type = PowerUpType.WALL_PASS;
                else type = PowerUpType.MAGNET;

                powerUps.add(new PowerUp(p, type));
            }
        }
    }

    private boolean isPowerUpAt(Point p) {
        for (PowerUp pu : powerUps) {
            if (pu.position.equals(p)) {
                return true;
            }
        }
        return false;
    }

    private void createEatEffect(Point p, Color color) {
        double x = p.x * TILE + TILE / 2.0;
        double y = p.y * TILE + TILE / 2.0;
        for (int i = 0; i < 10; i++) {
            particles.add(new Particle(x, y, color));
        }
        animations.add(new Animation(x, y, "+" + foodType.score, color));
    }

    private void loadHighScore() {
        try {
            java.util.Properties props = new java.util.Properties();
            java.io.File file = new java.io.File(HIGHSCORE_FILE);
            if (file.exists()) {
                try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
                    props.load(fis);
                    String value = props.getProperty("highscore", "0");
                    highScore = Integer.parseInt(value);
                }
            }
        } catch (Exception e) {
            // 加载失败，使用默认值0
            highScore = 0;
        }
    }

    private void saveHighScore() {
        try {
            java.util.Properties props = new java.util.Properties();
            props.setProperty("highscore", String.valueOf(highScore));
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(HIGHSCORE_FILE)) {
                props.store(fos, "Snake Game High Score");
            }
        } catch (Exception e) {
            // 保存失败，静默处理
        }
    }

    private void checkAchievements() {
        long currentTime = System.currentTimeMillis();
        long gameTime = currentTime - gameStartTime;

        for (Achievement a : achievements) {
            if (a.unlocked) continue;

            switch (a.id) {
                case "first_food":
                    if (totalFoodEaten >= 1) a.unlocked = true;
                    break;
                case "score_100":
                    if (score >= 100) a.unlocked = true;
                    break;
                case "score_500":
                    if (score >= 500) a.unlocked = true;
                    break;
                case "score_1000":
                    if (score >= 1000) a.unlocked = true;
                    break;
                case "combo_5":
                    if (comboCount >= 5) a.unlocked = true;
                    break;
                case "combo_10":
                    if (comboCount >= 10) a.unlocked = true;
                    break;
                case "level_5":
                    if (level >= 5) a.unlocked = true;
                    break;
                case "level_10":
                    if (level >= 10) a.unlocked = true;
                    break;
                case "food_50":
                    if (totalFoodEaten >= 50) a.unlocked = true;
                    break;
                case "food_100":
                    if (totalFoodEaten >= 100) a.unlocked = true;
                    break;
                case "time_5min":
                    if (gameTime >= 5 * 60 * 1000) a.unlocked = true;
                    break;
                case "time_10min":
                    if (gameTime >= 10 * 60 * 1000) a.unlocked = true;
                    break;
                case "monster_hunter":
                    if (totalMonstersKilled >= 1) a.unlocked = true;
                    break;
                case "sharpshooter":
                    if (consecutiveHits >= 10) a.unlocked = true;
                    break;
                case "untouchable":
                    if (!hitByMonsterThisLevel && foodEaten >= currentLevelConfig.foodTarget) a.unlocked = true;
                    break;
            }

            if (a.unlocked) {
                unlockedAchievements.add(a);
                // 显示成就解锁动画
                animations.add(new Animation(WIDTH / 2.0, HEIGHT / 2.0 - 50,
                    "成就解锁: " + a.name, GOLDEN_FOOD));
            }
        }
    }

    private void takeScreenshot() {
        try {
            // 创建截图文件名
            String filename = "snake_screenshot_" + System.currentTimeMillis() + ".png";
            java.io.File file = new java.io.File(filename);

            // 创建BufferedImage
            java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
                getWidth(), getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);

            // 绘制当前画面到图像
            Graphics2D g2d = image.createGraphics();
            paint(g2d);
            g2d.dispose();

            // 保存图像
            javax.imageio.ImageIO.write(image, "png", file);

            // 显示截图提示
            animations.add(new Animation(WIDTH / 2.0, HEIGHT / 2.0,
                "截图已保存: " + filename, SHIELD_COLOR));
        } catch (Exception e) {
            // 截图失败，静默处理
        }
    }

    // ── 游戏逻辑(每帧调用) ──────────────────────────────────
    private void tick() {
        if (!running || paused || gameOver) return;

        long currentTime = System.currentTimeMillis();

        // 更新食物动画
        if (currentTime - lastFoodAnimTime > FOOD_ANIM_INTERVAL) {
            foodAnimFrame = (foodAnimFrame + 1) % 4;
            lastFoodAnimTime = currentTime;
        }

        // 更新道具效果过期
        if (currentTime > powerUpExpireTime) {
            hasShield = false;
            canPassWall = currentLevelConfig.wallBounce; // 恢复关卡默认值，而非直接 false
            hasMagnet = false;
        }

        // 更新粒子和动画
        Iterator<Particle> particleIter = particles.iterator();
        while (particleIter.hasNext()) {
            Particle p = particleIter.next();
            p.update();
            if (p.isDead()) particleIter.remove();
        }

        Iterator<Animation> animIter = animations.iterator();
        while (animIter.hasNext()) {
            Animation a = animIter.next();
            a.update();
            if (a.isDead()) animIter.remove();
        }

        // 更新轨迹
        if (!snake.isEmpty()) {
            Point tail = snake.peekFirst();
            trail.add(new TrailPoint(tail.x, tail.y, 200));

            // 移除过期轨迹
            Iterator<TrailPoint> trailIter = trail.iterator();
            while (trailIter.hasNext()) {
                TrailPoint tp = trailIter.next();
                tp.alpha -= 40; // 每帧减少40透明度
                if (tp.alpha <= 0) {
                    trailIter.remove();
                }
            }
        }

        // 更新食物动画
        if (foodAnimation != null) {
            foodAnimation.update();
            if (foodAnimation.isComplete()) {
                foodAnimation = null;
            }
        }

        // 更新连击闪光
        if (comboCount >= 3) {
            comboFlashIntensity = Math.min(255, comboCount * 30);
            lastComboFlashTime = currentTime;
        } else {
            long elapsed = currentTime - lastComboFlashTime;
            if (elapsed > 1000) { // 1秒后消失
                comboFlashIntensity = 0;
            }
        }

        // 检查成就
        checkAchievements();

        // 应用方向
        dx = nextDx; dy = nextDy;

        // 控制反转效果
        if (controlReversed) {
            if (System.currentTimeMillis() > controlReversedEndTime) {
                controlReversed = false;
            } else {
                dx = -dx;
                dy = -dy;
            }
        }

        Point head = snake.peekLast();
        if (head == null) return; // 安全检查
        int nx = head.x + dx, ny = head.y + dy;

        // 穿墙处理
        if (canPassWall) {
            if (nx < 0) nx = COLS - 1;
            else if (nx >= COLS) nx = 0;
            if (ny < 0) ny = ROWS - 1;
            else if (ny >= ROWS) ny = 0;
        } else {
            // 撞墙检测
            if (nx < 0 || nx >= COLS || ny < 0 || ny >= ROWS) {
                if (hasShield) {
                    hasShield = false;
                    // 反弹效果
                    dx = -dx;
                    dy = -dy;
                    nx = head.x + dx;
                    ny = head.y + dy;
                    // 确保新位置有效
                    if (nx < 0 || nx >= COLS || ny < 0 || ny >= ROWS) {
                        // 如果反弹后仍然出界，游戏结束
                        endGame();
                        return;
                    }
                    // 触发轻微震动
                    screenShake = new ScreenShake(2, 200);
                } else {
                    endGame();
                    return;
                }
            }
        }

        Point newHead = new Point(nx, ny);

        // 撞障碍物检测
        if (obstacles.contains(newHead)) {
            if (hasShield) {
                hasShield = false;
                // 移除障碍物
                obstacles.remove(newHead);
                createEatEffect(newHead, OBSTACLE_COLOR);
                // 触发中等震动
                screenShake = new ScreenShake(4, 300);
            } else {
                endGame();
                return;
            }
        }

        // 撞自己检测(忽略尾巴, 因为它即将移走; 但如果吃了食物尾巴不移走)
        boolean willEat = newHead.equals(food);
        if (!willEat && snake.contains(newHead)) {
            if (hasShield) {
                hasShield = false;
            } else {
                endGame();
                return;
            }
        }

        // 在蛇移动前检测怪物碰撞（解决"穿过"问题）
        for (Monster m : monsters) {
            if (m.alive && m.position.equals(newHead)) {
                handleMonsterHit(m);
                if (gameOver) return;
                // 被怪物击中后，蛇可能被重置，需要重新获取状态
                if (lives <= 0) return;
                break;
            }
        }

        snake.addLast(newHead);

        // 更新怪物和子弹
        updateMonsters();
        updateBullets();
        checkMonsterCollisions();

        // 检查是否吃到食物
        if (willEat) {
            // 连击系统
            if (currentTime - lastEatTime < COMBO_TIMEOUT) {
                comboCount++;
            } else {
                comboCount = 1;
            }
            lastEatTime = currentTime;

            // 计算分数
            int foodScore = foodType.score;
            if (comboCount > 1) {
                foodScore += (comboCount - 1) * 5; // 连击额外加分
            }

            score += foodScore;
            if (score > highScore) highScore = score;

            // 根据食物类型增长蛇身
            for (int i = 1; i < foodType.growth; i++) {
                // 临时添加，下一帧会自然移除
                snake.addFirst(new Point(-1, -1)); // 临时位置
            }

            // 食物特效
            Color effectColor;
            switch (foodType) {
                case GOLDEN: effectColor = GOLDEN_FOOD; break;
                case BLUE: effectColor = BLUE_FOOD; break;
                case PURPLE: effectColor = PURPLE_FOOD; break;
                case GREEN: effectColor = GREEN_FOOD; break;
                default: effectColor = FOOD_COLOR;
            }
            createEatEffect(food, effectColor);

            // 食物特殊效果
            switch (foodType) {
                case BLUE: // 减速效果
                    timer.setDelay(Math.min(200, timer.getDelay() + 30));
                    break;
                case PURPLE: // 加速效果
                    timer.setDelay(Math.max(50, timer.getDelay() - 20));
                    break;
            }

            // 更新关卡系统
            foodEaten++;
            totalFoodEaten++;
            if (foodEaten >= currentLevelConfig.foodTarget) {
                // 过关！触发过关动画
                if (level < LEVELS.length) {
                    gameState = GameState.LEVEL_CLEAR;
                    levelClearStartTime = System.currentTimeMillis();
                    levelClearAnimFrame = 0;
                    // 生成过关烟花粒子
                    for (int i = 0; i < 50; i++) {
                        double px = WIDTH / 2.0 + (rand.nextDouble() - 0.5) * 200;
                        double py = HEIGHT / 2.0 + (rand.nextDouble() - 0.5) * 100;
                        Color c = new Color(rand.nextInt(256), rand.nextInt(256), rand.nextInt(256));
                        Particle p = new Particle(px, py, c);
                        p.dx = (rand.nextDouble() - 0.5) * 4;
                        p.dy = (rand.nextDouble() - 0.5) * 4;
                        p.life = 60;
                        p.maxLife = 60;
                        levelClearParticles.add(p);
                    }
                    return;
                } else {
                    // 已通关全部关卡，循环最后一关并提升难度
                    level++;
                    foodEaten = 0;
                    applyLevelConfig();
                    for (int i = 0; i < 3; i++) spawnObstacle();
                    obstacleCount = obstacles.size();
                    animations.add(new Animation(WIDTH / 2.0, HEIGHT / 2.0, 
                        "难度提升！", new Color(255, 100, 100)));
                }
            }

            // 生命恢复（每1000分）
            if (score % 1000 < foodScore && lives < 5) {
                lives++;
                animations.add(new Animation(head.x * TILE, head.y * TILE, "+1生命", SHIELD_COLOR));
            }

            // 生成新道具
            spawnPowerUp();

            spawnFood();
        } else {
            snake.pollFirst(); // 移除尾巴
        }

        // 检查道具碰撞
        Iterator<PowerUp> powerUpIter = powerUps.iterator();
        while (powerUpIter.hasNext()) {
            PowerUp pu = powerUpIter.next();
            if (newHead.equals(pu.position)) {
                // 获得道具效果并显示提示
                String effectName = "";
                Color effectColor = Color.WHITE;
                int durationSec = (int)(POWER_UP_DURATION / 1000); // 转换为秒
                switch (pu.type) {
                    case SHIELD:
                        hasShield = true;
                        effectName = "🛡️ 护盾 " + durationSec + "秒";
                        effectColor = SHIELD_COLOR;
                        break;
                    case WALL_PASS:
                        canPassWall = true;
                        effectName = "👻 穿墙 " + durationSec + "秒";
                        effectColor = new Color(200, 200, 255);
                        break;
                    case MAGNET:
                        hasMagnet = true;
                        effectName = "🧲 磁铁 " + durationSec + "秒";
                        effectColor = MAGNET_COLOR;
                        break;
                }
                powerUpExpireTime = currentTime + POWER_UP_DURATION;
                createEatEffect(pu.position, effectColor);
                // 显示道具名称提示（附带持续时间）
                animations.add(new Animation(
                    pu.position.x * TILE, 
                    pu.position.y * TILE - 10, 
                    effectName, 
                    effectColor
                ));
                powerUpIter.remove();
            }
        }

        // 磁铁效果：吸引附近食物
        if (hasMagnet && food != null) {
            int distX = food.x - head.x;
            int distY = food.y - head.y;
            if (Math.abs(distX) <= 3 && Math.abs(distY) <= 3) {
                // 移动食物向蛇头靠近
                if (distX > 0) food.x--;
                else if (distX < 0) food.x++;
                if (distY > 0) food.y--;
                else if (distY < 0) food.y++;

                // 确保食物位置有效
                food.x = Math.max(0, Math.min(COLS - 1, food.x));
                food.y = Math.max(0, Math.min(ROWS - 1, food.y));
            }
        }
    }

    private void updateMonsters() {
        long now = System.currentTimeMillis();

        for (Monster m : monsters) {
            if (!m.alive) continue;

            long moveInterval = m.type == MonsterType.TRACKER ? 800 : 600;  // 追踪怪更慢
            if (now - m.lastMoveTime < moveInterval) continue;

            m.lastMoveTime = now;

            if (m.type == MonsterType.WANDERER) {
                // 游荡怪：随机移动
                if (rand.nextInt(3) == 0) {  // 1/3概率改变方向
                    int[][] dirs = {{0,1},{0,-1},{1,0},{-1,0}};
                    int[] dir = dirs[rand.nextInt(4)];
                    m.dx = dir[0];
                    m.dy = dir[1];
                }
            } else {
                // 追踪怪：向蛇头方向移动
                Point head = snake.peekLast(); // ✅ 使用 peekLast 获取蛇头
                int diffX = head.x - m.position.x;
                int diffY = head.y - m.position.y;

                // 只改变一个轴向
                if (rand.nextBoolean()) {
                    m.dx = Integer.signum(diffX);
                    m.dy = 0;
                } else {
                    m.dx = 0;
                    m.dy = Integer.signum(diffY);
                }
            }

            // 计算新位置
            int newX = m.position.x + m.dx;
            int newY = m.position.y + m.dy;

            // 边界和障碍物检测
            if (newX < 0 || newX >= COLS || newY < 0 || newY >= ROWS ||
                obstacles.contains(new Point(newX, newY))) {
                // 反弹：反向
                m.dx = -m.dx;
                m.dy = -m.dy;
            } else {
                // 检查是否与其他怪物重叠
                Point newPos = new Point(newX, newY);
                boolean overlap = false;
                for (Monster other : monsters) {
                    if (other != m && other.alive && other.position.equals(newPos)) {
                        overlap = true;
                        break;
                    }
                }
                if (!overlap) {
                    m.position = newPos;
                }
            }
        }
    }

    private void updateBullets() {
        long now = System.currentTimeMillis();
        Iterator<Bullet> iter = bullets.iterator();

        while (iter.hasNext()) {
            Bullet b = iter.next();

            // 超时移除
            if (now - b.createTime > BULLET_LIFETIME) {
                iter.remove();
                continue;
            }

            // 保存尾迹
            b.trail.add(new Point((int)b.x / TILE, (int)b.y / TILE));
            if (b.trail.size() > 3) {
                b.trail.remove(0);
            }

            // 移动
            b.x += b.dx * BULLET_SPEED;
            b.y += b.dy * BULLET_SPEED;

            // 边界检测
            if (b.x < 0 || b.x >= WIDTH || b.y < 0 || b.y >= HEIGHT) {
                iter.remove();
                consecutiveMisses++;
                consecutiveHits = 0;
                continue;
            }

            // 障碍物检测
            int gridX = (int)b.x / TILE;
            int gridY = (int)b.y / TILE;
            if (obstacles.contains(new Point(gridX, gridY))) {
                iter.remove();
                consecutiveMisses++;
                consecutiveHits = 0;
            }
        }
    }

    private void checkMonsterCollisions() {
        // 子弹击中怪物
        Iterator<Bullet> bulletIter = bullets.iterator();
        while (bulletIter.hasNext()) {
            Bullet b = bulletIter.next();
            int bulletGridX = (int)b.x / TILE;
            int bulletGridY = (int)b.y / TILE;
            Point bulletPos = new Point(bulletGridX, bulletGridY);

            boolean hit = false;
            for (Monster m : monsters) {
                if (m.alive && m.position.equals(bulletPos)) {
                    m.health--;
                    hit = true;

                    if (m.health <= 0) {
                        m.alive = false;
                        score += m.type.score;
                        monstersKilled++;
                        totalMonstersKilled++;
                        consecutiveHits++;
                        consecutiveMisses = 0;

                        // 粒子效果
                        for (int i = 0; i < 10; i++) {
                            particles.add(new Particle(
                                m.position.x * TILE + TILE/2,
                                m.position.y * TILE + TILE/2,
                                m.type.color
                            ));
                        }

                        // 30%概率掉落道具
                        if (rand.nextDouble() < 0.3) {
                            PowerUpType[] types = PowerUpType.values();
                            powerUps.add(new PowerUp(m.position, types[rand.nextInt(types.length)]));
                        }
                    }
                    break;
                }
            }

            if (hit) {
                bulletIter.remove();
            }
        }

        // 怪物碰到蛇（检测蛇头）
        Point head = snake.peekLast(); // ✅ 使用 peekLast 获取蛇头
        if (head != null) {
            for (Monster m : monsters) {
                if (m.alive && m.position.equals(head)) {
                    handleMonsterHit(m);
                    break;
                }
            }
        }
    }

    private void handleMonsterHit(Monster m) {
        double roll = rand.nextDouble();
        Point monsterPos = m.position; // 怪物位置用于显示提示

        if (roll < 0.4) {
            // 40% 失去一条命
            lives--;
            screenShake = new ScreenShake(6, 500);
            hitByMonsterThisLevel = true;
            animations.add(new Animation(monsterPos.x * TILE, monsterPos.y * TILE, "-1命！", FOOD_COLOR));
            if (lives <= 0) {
                gameOver = true;
                running = false;
                gameState = GameState.GAME_OVER;
                timer.stop();
                totalGameTime = System.currentTimeMillis() - gameStartTime;
                if (score > highScore) {
                    highScore = score;
                    saveHighScore();
                }
                if (level > maxLevel) {
                    maxLevel = level;
                }
            } else {
                // 只重置蛇位置，不刷新地图
                resetSnakePosition();
                // 清除道具效果
                hasShield = false;
                hasMagnet = false;
                controlReversed = false;
                // 进度惩罚
                foodEaten = Math.max(0, foodEaten - 3);
            }
        } else if (roll < 0.8) {
            // 40% 控制反转3秒
            controlReversed = true;
            controlReversedEndTime = System.currentTimeMillis() + 3000;
            hitByMonsterThisLevel = true;
            screenShake = new ScreenShake(3, 200);
            animations.add(new Animation(monsterPos.x * TILE, monsterPos.y * TILE, "控制反转！", new Color(255, 100, 100)));
        } else {
            // 20% 击退效果 - 蛇被向后推
            screenShake = new ScreenShake(4, 300);
            hitByMonsterThisLevel = true;
            animations.add(new Animation(monsterPos.x * TILE, monsterPos.y * TILE, "击退！", new Color(255, 200, 0)));
            Point head = snake.peekLast(); // 获取蛇头
            if (head != null && snake.size() > 1) { // 确保蛇长度大于1
                // 向蛇移动的反方向推退
                int pushDx = -dx;
                int pushDy = -dy;
                int pushCount = Math.min(3, snake.size() - 1); // 最多推退数量不超过蛇身长度
                for (int i = 0; i < pushCount; i++) {
                    int newX = head.x + pushDx;
                    int newY = head.y + pushDy;
                    // 检查新位置是否有效（边界、障碍物、蛇身）
                    Point newPos = new Point(newX, newY);
                    if (newX >= 0 && newX < COLS && newY >= 0 && newY < ROWS &&
                        !obstacles.contains(newPos) && !snake.contains(newPos)) {
                        snake.addLast(newPos); // 在蛇头前面添加
                        snake.removeFirst(); // 移除蛇尾
                        head = snake.peekLast(); // 更新蛇头引用
                    } else {
                        break; // 碰到边界、障碍物或自身停止
                    }
                }
            }
        }

        // 怪物消失
        m.alive = false;
    }

    private void resetSnakePosition() {
        snake.clear();
        int startX = COLS / 2, startY = ROWS / 2;
        // 创建3节蛇身，与初始状态一致
        for (int i = 2; i >= 0; i--) snake.addFirst(new Point(startX - i, startY));
        dx = 1; dy = 0;
        nextDx = 1; nextDy = 0;
        // 清除轨迹，避免显示异常
        trail.clear();
    }

    private void endGame() {
        if (lives > 1) {
            lives--;
            // 重新开始当前关卡（保持关卡不变）
            snake.clear();
            int startX = COLS / 2, startY = ROWS / 2;
            for (int i = 2; i >= 0; i--) snake.add(new Point(startX - i, startY));
            dx = 1; dy = 0;
            nextDx = 1; nextDy = 0;
            hasShield = false;
            canPassWall = currentLevelConfig.wallBounce; // 保持关卡自带的穿墙
            hasMagnet = false;
            powerUpExpireTime = 0;
            foodEaten = Math.max(0, foodEaten - 3); // 死亡惩罚：进度倒退3个
            animations.add(new Animation(WIDTH/2.0, HEIGHT/2.0, "失去1命！进度-3", FOOD_COLOR));

            // 重新生成障碍物
            applyLevelConfig();
            spawnFood();

            // 触发中等震动
            screenShake = new ScreenShake(4, 300);
        } else {
            gameOver = true;
            running = false;
            gameState = GameState.GAME_OVER;
            timer.stop();

            // 计算总游戏时间
            totalGameTime = System.currentTimeMillis() - gameStartTime;

            // 保存最高分和最高关卡
            if (score > highScore) {
                highScore = score;
                saveHighScore();
            }
            if (level > maxLevel) {
                maxLevel = level;
            }

            // 触发强烈震动
            screenShake = new ScreenShake(6, 500);
        }
    }

    // ── 绘制 ────────────────────────────────────────────────
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // 菜单状态
        if (gameState == GameState.MENU) {
            drawMenu(g2);
            return;
        }

        // 过关动画状态
        if (gameState == GameState.LEVEL_CLEAR) {
            drawLevelClear(g2);
            return;
        }

        // 应用屏幕震动效果
        if (screenShake != null && screenShake.isActive()) {
            screenShake.update();
            g2.translate(screenShake.offsetX, screenShake.offsetY);
        }

        // 使用关卡主题色绘制背景
        setBackground(currentLevelConfig.bgColor);

        // 网格线
        g2.setColor(currentLevelConfig.gridColor);
        for (int x = 0; x <= COLS; x++) g2.drawLine(x * TILE, 0, x * TILE, ROWS * TILE);
        for (int y = 0; y <= ROWS; y++) g2.drawLine(0, y * TILE, WIDTH, y * TILE);

        // 关卡边框装饰
        g2.setColor(currentLevelConfig.borderColor);
        g2.setStroke(new BasicStroke(3));
        g2.drawRect(0, 0, WIDTH, HEIGHT);
        g2.setStroke(new BasicStroke(1));

        // 障碍物
        g2.setColor(OBSTACLE_COLOR);
        for (Point obs : obstacles) {
            g2.fillRect(obs.x * TILE + 1, obs.y * TILE + 1, TILE - 2, TILE - 2);
            // 障碍物纹理
            g2.setColor(new Color(80, 80, 80));
            g2.drawLine(obs.x * TILE + 5, obs.y * TILE + 5,
                       obs.x * TILE + TILE - 5, obs.y * TILE + TILE - 5);
            g2.drawLine(obs.x * TILE + TILE - 5, obs.y * TILE + 5,
                       obs.x * TILE + 5, obs.y * TILE + TILE - 5);
            g2.setColor(OBSTACLE_COLOR);
        }

        // 绘制怪物
        for (Monster m : monsters) {
            if (!m.alive) continue;

            int x = m.position.x * TILE;
            int y = m.position.y * TILE;

            // 怪物身体
            g2.setColor(m.type.color);
            g2.fillRoundRect(x + 2, y + 2, TILE - 4, TILE - 4, 8, 8);

            // 怪物眼睛
            g2.setColor(Color.WHITE);
            g2.fillOval(x + 5, y + 6, 4, 4);
            g2.fillOval(x + TILE - 9, y + 6, 4, 4);
            g2.setColor(Color.BLACK);
            g2.fillOval(x + 6, y + 7, 2, 2);
            g2.fillOval(x + TILE - 8, y + 7, 2, 2);

            // 追踪怪标记
            if (m.type == MonsterType.TRACKER) {
                g2.setColor(Color.YELLOW);
                g2.setFont(new Font("Arial", Font.BOLD, 10));
                g2.drawString("T", x + 7, y + 15);
            }

            // 生命值显示（多血怪物）
            if (m.type.health > 1) {
                g2.setColor(Color.RED);
                for (int i = 0; i < m.health; i++) {
                    g2.fillOval(x + 2 + i * 6, y - 4, 4, 4);
                }
            }
        }

        // 绘制子弹
        for (Bullet b : bullets) {
            // 尾迹
            for (int i = 0; i < b.trail.size(); i++) {
                Point t = b.trail.get(i);
                int alpha = 50 + i * 30;
                g2.setColor(new Color(255, 200, 0, alpha));
                g2.fillOval(t.x * TILE + 8, t.y * TILE + 8, 4, 4);
            }

            // 子弹主体
            g2.setColor(BULLET_COLOR);
            g2.fillOval((int)b.x - 3, (int)b.y - 3, 6, 6);
        }

        // 道具
        for (PowerUp pu : powerUps) {
            int x = pu.position.x * TILE;
            int y = pu.position.y * TILE;
            switch (pu.type) {
                case SHIELD:
                    g2.setColor(SHIELD_COLOR);
                    // 画星星形状
                    Polygon star = new Polygon();
                    for (int i = 0; i < 5; i++) {
                        double angle = Math.toRadians(i * 72 - 90);
                        star.addPoint(x + TILE/2 + (int)(Math.cos(angle) * 8),
                                     y + TILE/2 + (int)(Math.sin(angle) * 8));
                        angle = Math.toRadians(i * 72 + 36 - 90);
                        star.addPoint(x + TILE/2 + (int)(Math.cos(angle) * 4),
                                     y + TILE/2 + (int)(Math.sin(angle) * 4));
                    }
                    g2.fillPolygon(star);
                    break;
                case WALL_PASS:
                    g2.setColor(WALL_PASS_COLOR);
                    g2.fillOval(x + 3, y + 3, TILE - 6, TILE - 6);
                    g2.setColor(Color.BLACK);
                    g2.drawOval(x + 3, y + 3, TILE - 6, TILE - 6);
                    break;
                case MAGNET:
                    g2.setColor(MAGNET_COLOR);
                    g2.fillRect(x + 4, y + 4, TILE - 8, TILE - 8);
                    g2.setColor(Color.BLACK);
                    g2.drawRect(x + 4, y + 4, TILE - 8, TILE - 8);
                    break;
            }
        }

        // 绘制轨迹
        for (TrailPoint tp : trail) {
            Color trailColor = new Color(
                colorTheme.bodyColor.getRed(),
                colorTheme.bodyColor.getGreen(),
                colorTheme.bodyColor.getBlue(),
                tp.alpha
            );
            g2.setColor(trailColor);
            g2.fillRoundRect(tp.x * TILE + 2, tp.y * TILE + 2, TILE - 4, TILE - 4, 4, 4);
        }

        // 食物（带动画效果）
        if (food != null) {
            Color foodColor;
            switch (foodType) {
                case GOLDEN: foodColor = GOLDEN_FOOD; break;
                case BLUE: foodColor = BLUE_FOOD; break;
                case PURPLE: foodColor = PURPLE_FOOD; break;
                case GREEN: foodColor = GREEN_FOOD; break;
                default: foodColor = FOOD_COLOR;
            }

            g2.setColor(foodColor);

            // 计算动画缩放
            double scale = 1.0;
            if (foodAnimation != null) {
                scale = foodAnimation.scale;
            }

            int offset = 0;
            if (foodType != FoodType.NORMAL) {
                // 特殊食物有闪烁效果
                offset = (foodAnimFrame % 2 == 0) ? 2 : 0;
            }

            // 应用缩放效果
            int size = (int)((TILE - 4 - offset * 2) * scale);
            int x = food.x * TILE + 2 + offset + (TILE - 4 - offset * 2 - size) / 2;
            int y = food.y * TILE + 2 + offset + (TILE - 4 - offset * 2 - size) / 2;

            g2.fillRoundRect(x, y, size, size, 8, 8);

            // 食物类型标记
            if (scale > 0.5) { // 只在缩放足够大时显示标记
                g2.setColor(Color.WHITE);
                g2.setFont(new Font("Arial", Font.BOLD, 10));
                String mark = "";
                switch (foodType) {
                    case GOLDEN: mark = "G"; break;
                    case BLUE: mark = "B"; break;
                    case PURPLE: mark = "P"; break;
                    case GREEN: mark = "+"; break;
                }
                if (!mark.isEmpty()) {
                    FontMetrics fm = g2.getFontMetrics();
                    int markX = food.x * TILE + (TILE - fm.stringWidth(mark)) / 2;
                    int markY = food.y * TILE + (TILE + fm.getAscent() - fm.getDescent()) / 2;
                    g2.drawString(mark, markX, markY);
                }
            }
        }

        // 蛇（渐变色效果，使用颜色主题）
        boolean first = true;
        int snakeSize = snake.size();
        int index = 0;
        for (Point p : snake) {
            if (first) {
                g2.setColor(colorTheme.headColor);
                first = false;
            } else {
                // 渐变色：头部亮，尾部暗，使用颜色主题
                float ratio = (float) index / snakeSize;
                int red = (int)(colorTheme.bodyColor.getRed() - ratio * (colorTheme.bodyColor.getRed() * 0.3));
                int green = (int)(colorTheme.bodyColor.getGreen() - ratio * (colorTheme.bodyColor.getGreen() * 0.3));
                int blue = (int)(colorTheme.bodyColor.getBlue() - ratio * (colorTheme.bodyColor.getBlue() * 0.3));
                g2.setColor(new Color(
                    Math.max(0, red),
                    Math.max(0, green),
                    Math.max(0, blue)
                ));
            }

            g2.fillRoundRect(p.x * TILE + 1, p.y * TILE + 1, TILE - 2, TILE - 2, 6, 6);

            // 蛇头眼睛
            if (index == snakeSize - 1) {
                g2.setColor(Color.WHITE);
                int eyeX = p.x * TILE + (dx == 1 ? 14 : 6);
                int eyeY = p.y * TILE + (dy == 1 ? 14 : 6);
                g2.fillOval(eyeX, eyeY, 4, 4);
            }

            index++;
        }

        // 护盾效果
        if (hasShield && !snake.isEmpty()) {
            Point head = snake.peekLast();
            g2.setColor(new Color(colorTheme.headColor.getRed(),
                                 colorTheme.headColor.getGreen(),
                                 colorTheme.headColor.getBlue(), 100));
            g2.fillOval(head.x * TILE - 3, head.y * TILE - 3, TILE + 6, TILE + 6);
        }

        // 粒子特效
        for (Particle p : particles) {
            p.draw(g2);
        }

        // 动画文字
        for (Animation a : animations) {
            a.draw(g2);
        }

        // 连击闪光效果
        if (gameState == GameState.PLAYING) {
            drawComboFlash(g2);
        }

        // 暂停覆盖层
        if (gameState == GameState.PAUSED) {
            drawPauseOverlay(g2);
        }

        // 状态栏
        g2.setColor(TEXT_COLOR);
        g2.setFont(new Font("Microsoft YaHei", Font.BOLD, 14));

        // 第一行：分数、关卡名、连击
        String line1 = String.format("得分: %d  关卡%d: %s  连击: %d",
                                   score, level, currentLevelConfig.name, comboCount > 1 ? comboCount : 0);
        g2.drawString(line1, 8, ROWS * TILE + 20);

        // 第二行：生命、关卡进度、道具状态
        StringBuilder line2 = new StringBuilder();
        line2.append("生命: ");
        for (int i = 0; i < lives; i++) line2.append("♥ ");
        line2.append(String.format(" 进度: %d/%d", foodEaten, currentLevelConfig.foodTarget));

        if (hasShield) line2.append(" 护盾");
        if (canPassWall && !currentLevelConfig.wallBounce) line2.append(" 穿墙");
        if (hasMagnet) line2.append(" 磁铁");
        line2.append(String.format(" 子弹: %d/%d", bullets.size(), MAX_BULLETS));

        if (controlReversed) {
            long remaining = (controlReversedEndTime - System.currentTimeMillis()) / 1000;
            line2.append(String.format(" 控制反转: %d秒", remaining));
        }

        if (paused) line2.append(" [暂停]");
        if (gameOver) line2.append(" [游戏结束!]");

        g2.drawString(line2.toString(), 8, ROWS * TILE + 40);

        // 第三行：食物计数、游戏时间
        long gameTime = 0;
        if (gameState == GameState.PLAYING) {
            gameTime = System.currentTimeMillis() - gameStartTime;
        } else if (gameState == GameState.GAME_OVER) {
            gameTime = totalGameTime;
        }
        long seconds = gameTime / 1000;
        long minutes = seconds / 60;
        seconds = seconds % 60;
        String line3 = String.format("食物: %d  时间: %02d:%02d", totalFoodEaten, minutes, seconds);
        g2.drawString(line3, 8, ROWS * TILE + 60);

        // 第四行：控制提示
        g2.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        String line4 = "W/A/S/D移动 | 鼠标左键射击 | 空格暂停 | R重开 | ESC返回菜单 | P截图";
        g2.drawString(line4, 8, ROWS * TILE + 78);

        // 画面中央提示（游戏结束）
        if (gameOver) {
            g2.setFont(new Font("Microsoft YaHei", Font.BOLD, 36));
            String msg = "游戏结束";
            FontMetrics fm = g2.getFontMetrics();
            int tx = (WIDTH - fm.stringWidth(msg)) / 2;
            int ty = (ROWS * TILE) / 2;
            // 半透明背景
            g2.setColor(new Color(0, 0, 0, 160));
            g2.fillRoundRect(tx - 20, ty - 36, fm.stringWidth(msg) + 40, fm.getHeight() + 20, 12, 12);
            g2.setColor(FOOD_COLOR);
            g2.drawString(msg, tx, ty);
            g2.setFont(new Font("Microsoft YaHei", Font.PLAIN, 16));
            String sub = String.format("最终得分: %d  到达关卡: %d (%s)  最高分: %d", 
                score, level, currentLevelConfig.name, highScore);
            fm = g2.getFontMetrics();
            g2.setColor(TEXT_COLOR);
            g2.drawString(sub, (WIDTH - fm.stringWidth(sub)) / 2, ty + 30);
            String restart = "按 R 重新开始 | ESC 返回菜单";
            fm = g2.getFontMetrics();
            g2.drawString(restart, (WIDTH - fm.stringWidth(restart)) / 2, ty + 50);

            // 显示解锁的成就
            if (!unlockedAchievements.isEmpty()) {
                g2.setFont(new Font("Microsoft YaHei", Font.BOLD, 14));
                String achievementTitle = "解锁成就:";
                fm = g2.getFontMetrics();
                g2.setColor(GOLDEN_FOOD);
                g2.drawString(achievementTitle, (WIDTH - fm.stringWidth(achievementTitle)) / 2, ty + 80);

                g2.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
                int achievementY = ty + 100;
                for (Achievement a : unlockedAchievements) {
                    String achievementText = "• " + a.name + ": " + a.description;
                    fm = g2.getFontMetrics();
                    g2.setColor(TEXT_COLOR);
                    g2.drawString(achievementText, (WIDTH - fm.stringWidth(achievementText)) / 2, achievementY);
                    achievementY += 20;
                }
            }
        }
    }

    private void drawMenu(Graphics2D g2) {
        // 绘制背景网格
        g2.setColor(GRID);
        for (int x = 0; x <= COLS; x++) g2.drawLine(x * TILE, 0, x * TILE, ROWS * TILE);
        for (int y = 0; y <= ROWS; y++) g2.drawLine(0, y * TILE, WIDTH, y * TILE);

        // 半透明覆盖层
        g2.setColor(new Color(0, 0, 0, 180));
        g2.fillRect(0, 0, WIDTH, HEIGHT);

        // 游戏标题
        g2.setFont(new Font("Microsoft YaHei", Font.BOLD, 48));
        String title = "贪吃蛇增强版";
        FontMetrics fm = g2.getFontMetrics();
        int titleX = (WIDTH - fm.stringWidth(title)) / 2;
        int titleY = HEIGHT / 3;

        // 标题阴影
        g2.setColor(new Color(0, 100, 0));
        g2.drawString(title, titleX + 2, titleY + 2);
        // 标题主体
        g2.setColor(SNAKE_HEAD);
        g2.drawString(title, titleX, titleY);

        // 副标题
        g2.setFont(new Font("Microsoft YaHei", Font.PLAIN, 18));
        String subtitle = "经典游戏，全新体验";
        fm = g2.getFontMetrics();
        g2.setColor(TEXT_COLOR);
        g2.drawString(subtitle, (WIDTH - fm.stringWidth(subtitle)) / 2, titleY + 40);

        // 颜色选择
        g2.setFont(new Font("Microsoft YaHei", Font.BOLD, 16));
        String colorTitle = "选择蛇的颜色：";
        fm = g2.getFontMetrics();
        g2.setColor(TEXT_COLOR);
        g2.drawString(colorTitle, (WIDTH - fm.stringWidth(colorTitle)) / 2, titleY + 90);

        // 颜色选项
        SnakeColorTheme[] themes = SnakeColorTheme.values();
        int startX = WIDTH / 2 - 150;
        for (int i = 0; i < themes.length; i++) {
            int x = startX + i * 80;
            int y = titleY + 110;

            // 选中状态
            if (themes[i] == colorTheme) {
                g2.setColor(new Color(255, 255, 255, 100));
                g2.fillRoundRect(x - 5, y - 5, 70, 40, 10, 10);
            }

            // 颜色方块
            g2.setColor(themes[i].headColor);
            g2.fillRoundRect(x, y, 20, 20, 5, 5);

            // 编号和名称
            g2.setColor(TEXT_COLOR);
            g2.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
            g2.drawString((i + 1) + "", x + 25, y + 15);
            g2.drawString(themes[i].name, x, y + 35);
        }

        // 控制说明
        g2.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));
        String[] controls = {
            "W/A/S/D 或 方向键 - 移动",
            "鼠标左键 - 射击",
            "空格 - 暂停游戏",
            "R - 重新开始",
            "ESC - 返回菜单",
            "P - 截图保存"
        };
        int controlY = titleY + 160;
        for (String control : controls) {
            fm = g2.getFontMetrics();
            g2.setColor(TEXT_COLOR);
            g2.drawString(control, (WIDTH - fm.stringWidth(control)) / 2, controlY);
            controlY += 22;
        }

        // 开始提示
        g2.setFont(new Font("Microsoft YaHei", Font.BOLD, 20));
        String startHint = "按任意键开始游戏";
        fm = g2.getFontMetrics();

        // 闪烁效果
        long time = System.currentTimeMillis();
        if ((time / 500) % 2 == 0) {
            g2.setColor(GOLDEN_FOOD);
        } else {
            g2.setColor(TEXT_COLOR);
        }
        g2.drawString(startHint, (WIDTH - fm.stringWidth(startHint)) / 2, HEIGHT - 40);
    }

    private void drawPauseOverlay(Graphics2D g2) {
        // 半透明覆盖层
        g2.setColor(new Color(0, 0, 0, 120));
        g2.fillRect(0, 0, WIDTH, HEIGHT);

        // 暂停文字
        g2.setFont(new Font("Microsoft YaHei", Font.BOLD, 36));
        String pauseText = "游戏暂停";
        FontMetrics fm = g2.getFontMetrics();
        int textX = (WIDTH - fm.stringWidth(pauseText)) / 2;
        int textY = HEIGHT / 2 - 20;

        g2.setColor(TEXT_COLOR);
        g2.drawString(pauseText, textX, textY);

        // 当前状态
        g2.setFont(new Font("Microsoft YaHei", Font.PLAIN, 16));
        String statusText = String.format("分数: %d  关卡: %d  生命: %d", score, level, lives);
        fm = g2.getFontMetrics();
        g2.drawString(statusText, (WIDTH - fm.stringWidth(statusText)) / 2, textY + 40);

        // 继续提示
        g2.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));
        String continueHint = "按空格键继续";
        fm = g2.getFontMetrics();
        g2.setColor(GOLDEN_FOOD);
        g2.drawString(continueHint, (WIDTH - fm.stringWidth(continueHint)) / 2, textY + 70);
    }

    private void drawComboFlash(Graphics2D g2) {
        if (comboFlashIntensity <= 0) return;

        // 根据连击数选择颜色
        Color flashColor;
        if (comboCount >= 9) {
            flashColor = GOLDEN_FOOD;
        } else if (comboCount >= 6) {
            flashColor = PURPLE_FOOD;
        } else {
            flashColor = BLUE_FOOD;
        }

        // 创建渐变闪光效果
        int alpha = comboFlashIntensity / 3; // 降低透明度

        // 上边框
        g2.setColor(new Color(flashColor.getRed(), flashColor.getGreen(), flashColor.getBlue(), alpha));
        g2.fillRect(0, 0, WIDTH, 10);

        // 下边框
        g2.fillRect(0, HEIGHT - 10, WIDTH, 10);

        // 左边框
        g2.fillRect(0, 0, 10, HEIGHT);

        // 右边框
        g2.fillRect(WIDTH - 10, 0, 10, HEIGHT);

        // 角落加强效果
        int cornerAlpha = alpha * 2;
        g2.setColor(new Color(flashColor.getRed(), flashColor.getGreen(), flashColor.getBlue(),
                             Math.min(255, cornerAlpha)));
        g2.fillRoundRect(0, 0, 30, 30, 10, 10);
        g2.fillRoundRect(WIDTH - 30, 0, 30, 30, 10, 10);
        g2.fillRoundRect(0, HEIGHT - 30, 30, 30, 10, 10);
        g2.fillRoundRect(WIDTH - 30, HEIGHT - 30, 30, 30, 10, 10);
    }

    // 绘制过关动画
    private void drawLevelClear(Graphics2D g2) {
        long elapsed = System.currentTimeMillis() - levelClearStartTime;
        
        // 背景渐暗
        int bgAlpha = Math.min(200, (int)(elapsed / 10));
        g2.setColor(new Color(0, 0, 0, bgAlpha));
        g2.fillRect(0, 0, WIDTH, HEIGHT);

        // 更新和绘制烟花粒子
        Iterator<Particle> iter = levelClearParticles.iterator();
        while (iter.hasNext()) {
            Particle p = iter.next();
            p.update();
            if (p.isDead()) iter.remove();
            else p.draw(g2);
        }

        // 关卡名称（从大到小弹入效果）
        float titleProgress = Math.min(1.0f, elapsed / 500.0f);
        float titleScale = 1.0f + (1.0f - titleProgress) * 0.5f; // 从1.5倍缩小到1倍
        int fontSize = (int)(48 * titleScale);
        g2.setFont(new Font("Microsoft YaHei", Font.BOLD, fontSize));
        String levelName = "第 " + level + " 关";
        FontMetrics fm = g2.getFontMetrics();
        int tx = (WIDTH - fm.stringWidth(levelName)) / 2;
        int ty = HEIGHT / 3;

        // 标题阴影
        g2.setColor(new Color(0, 0, 0, 150));
        g2.drawString(levelName, tx + 3, ty + 3);
        // 标题主体
        g2.setColor(currentLevelConfig.borderColor);
        g2.drawString(levelName, tx, ty);

        // 关卡主题名
        if (elapsed > 300) {
            g2.setFont(new Font("Microsoft YaHei", Font.BOLD, 32));
            String themeName = "「" + currentLevelConfig.name + "」";
            fm = g2.getFontMetrics();
            g2.setColor(TEXT_COLOR);
            g2.drawString(themeName, (WIDTH - fm.stringWidth(themeName)) / 2, ty + 50);
        }

        // 关卡描述
        if (elapsed > 600) {
            g2.setFont(new Font("Microsoft YaHei", Font.PLAIN, 18));
            fm = g2.getFontMetrics();
            g2.setColor(new Color(200, 200, 200));
            g2.drawString(currentLevelConfig.description, (WIDTH - fm.stringWidth(currentLevelConfig.description)) / 2, ty + 90);
        }

        // 过关信息
        if (elapsed > 900) {
            g2.setFont(new Font("Microsoft YaHei", Font.PLAIN, 16));
            String info = String.format("得分: %d  |  用时: %02d:%02d", score, 
                (System.currentTimeMillis() - gameStartTime) / 60000,
                ((System.currentTimeMillis() - gameStartTime) / 1000) % 60);
            fm = g2.getFontMetrics();
            g2.setColor(GOLDEN_FOOD);
            g2.drawString(info, (WIDTH - fm.stringWidth(info)) / 2, ty + 130);
        }

        // 下一关提示
        if (elapsed > 1500) {
            g2.setFont(new Font("Microsoft YaHei", Font.BOLD, 20));
            String nextHint = "按任意键进入下一关";
            fm = g2.getFontMetrics();
            
            // 闪烁效果
            long time = System.currentTimeMillis();
            g2.setColor((time / 500) % 2 == 0 ? GOLDEN_FOOD : TEXT_COLOR);
            g2.drawString(nextHint, (WIDTH - fm.stringWidth(nextHint)) / 2, HEIGHT - 80);
        }

        // 进度条（显示剩余时间）
        float progress = Math.min(1.0f, elapsed / (float)LEVEL_CLEAR_DURATION);
        g2.setColor(new Color(60, 60, 60));
        g2.fillRoundRect(WIDTH / 2 - 100, HEIGHT - 40, 200, 8, 4, 4);
        g2.setColor(currentLevelConfig.borderColor);
        g2.fillRoundRect(WIDTH / 2 - 100, HEIGHT - 40, (int)(200 * progress), 8, 4, 4);

        // 超时自动进入下一关
        if (elapsed >= LEVEL_CLEAR_DURATION) {
            advanceToNextLevel();
        }
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        tick();
        repaint();
    }

    // ── 入口 ────────────────────────────────────────────────
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Snake Enhanced  ·  W/A/S/D移动  Space暂停  R重开  ESC返回菜单  P截图");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setResizable(false);
            frame.add(new SnakeGame());
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }
}