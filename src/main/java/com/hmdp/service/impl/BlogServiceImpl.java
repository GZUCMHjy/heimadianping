package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.print.DocFlavor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Resource
    private IFollowService followService;
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户(方法引用)
        records.forEach(
                blog -> {
                    this.queryBlogUser(blog);
                    this.isBlogLiked(blog);
                }
        );
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if(blog == null){
            return Result.fail("笔记不存在");
        }
        // 查询blog有关的用户
        queryBlogUser(blog);
        // 查询是否点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        // 获取当前用户
        UserDTO user = UserHolder.getUser();
        if(user == null){
            // 用户未登录
            return ;
        }
        Long userId = UserHolder.getUser().getId();
        // 判断当前登录用户是否已经点赞
        String key = "blog:liked:" + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    /**
     * 点赞功能
     * @param id
     * @return
     */
    @Override
    public Result likeBlog(Long id) {
        // 获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 判断当前登录用户是否已经点赞
        String key = BLOG_LIKED_KEY + id;
        Double score= stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if(score == null){
            // 不存在 说明没点赞
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            if(isSuccess){
                // 换成sorted-set集合 加上时间戳（key value score）
                stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }
        }else{
            // 存在
            // 取消点赞
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            // set集合移除
            stringRedisTemplate.opsForZSet().remove(key,userId.toString());
        }
        return Result.ok();
    }

    /**
     * 实现点赞top5
     * @param id
     * @return
     */
    @Override
    public Result queryBlogLikes(Long id) {
        String key = BLOG_LIKED_KEY + id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        // 防止空指针异常
        if(top5.isEmpty() || top5 == null){
           return Result.ok(Collections.emptyList());
        }
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        // 拼接id
        String idStr = StrUtil.join(",", ids);

        // 根据用户id查询用户
        List<UserDTO> userDTOS = userService.query()
                .in("id",ids).last("ORDER BY FIELD(id," + idStr + ")").list()// 拼接sql
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(userDTOS);
    }

    /**
     * （博主）保存并推送笔记（给粉丝）
     * @param blog
     * @return
     */
    @Override
    public Result saveBlog(Blog blog) {
        // 获取当前发布笔记的博主
        UserDTO user = UserHolder.getUser();
        // 保存笔记
        boolean isSuccess = save(blog);
        // 判断是否保存成功
        if(!isSuccess){
            return Result.fail("发布失败");
        };
        // 并把博客笔记推送给粉丝
        // 找粉丝
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        for (Follow follow : follows) {
            // 存入sortedSet（Redis）—— 推送笔记
            String key = "feed:" + follow.getUserId();
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
        }
        // 返回结果
        return Result.ok("发布成功");
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 1. 获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 2. 获取博主发给粉丝的所有笔记
        String key = "feed:" + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate
                .opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        if(typedTuples == null || typedTuples.isEmpty()){
            return Result.ok();
        }
        // 解析offset max 和 blogId
        // 定义好大小（扩容影响性能Set集合大小大于默认初始List大小16）
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int count = 1;
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            // 4.1 获取id
            ids.add(Long.valueOf(tuple.getValue()));
            // 4.2 时间戳
            long time = tuple.getScore().longValue();
            if(time == minTime){
                count++;
            }else{
                minTime = time;
                count = 1;
            }
        }
        String idStr = StrUtil.join(",",ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();

        for (Blog blog : blogs) {
            queryBlogUser(blog);
            isBlogLiked(blog);
        }
        // 封装返回
        ScrollResult sr  = new ScrollResult();
        sr.setList(blogs);
        sr.setOffset(count);
        sr.setMinTime(minTime);
        return null;
    }

    private void queryBlogUser(Blog blog){
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
