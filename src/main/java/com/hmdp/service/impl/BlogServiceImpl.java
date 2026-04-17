package com.hmdp.service.impl;

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
import com.hmdp.utils.UserHolder;
import com.hmdp.vo.BlogVo;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.FEED_KEY;

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
    private IFollowService followService;

    @Resource
    private UserServiceImpl userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在！");
        }
        //查询用户
        BlogVo blogVo = new BlogVo();
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        BeanUtils.copyProperties(blog,blogVo);
        blogVo.setIcon(user.getIcon());
        blogVo.setName(user.getNickName());
        //查询博客是否被点赞
        isBlogLiked(blogVo);

        return Result.ok(blogVo);

    }

    private void isBlogLiked(BlogVo blogVo) {
        Long id = blogVo.getId();
        String key = "blog:liked:" + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, id.toString());
        blogVo.setIsLike(score!=null);

        }


    @Override
    public Result likeBlog(Long id) {
        Long userId = UserHolder.getUser().getId();
        String key = "blog:liked:" + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score != null) {
            //如果已经点赞，取消点赞
            stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            update().setSql("liked = liked - 1").eq("id", id).update();
        } else {
            //否则，点赞
            stringRedisTemplate.opsForZSet().add(key, userId.toString(),System.currentTimeMillis());
            update().setSql("liked = liked + 1").eq("id", id).update();
        }
        return Result.ok();


    }

    @Override
    /**
     * 查询笔记点赞top5
     * @param id 笔记id
     * @return
     */
    public Result queryBlogLikes(Long id) {
        String key = "blog:liked:" + id;
        Set<String> userIds = stringRedisTemplate.opsForZSet().range(key, 0, 4);

        if (userIds == null || userIds.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        List<BlogVo> blogVos = userIds.stream()
                .map(userId -> {
                    User user = userService.getById(Long.valueOf(userId));
                    BlogVo blogVo = new BlogVo();
                    BeanUtils.copyProperties(user, blogVo);
                    return blogVo;
                })
                .collect(Collectors.toList());

        return Result.ok(blogVos);
    }

    @Override
    public Result queryUserBlogs(Long userId, Integer current) {
        Page<Blog> page = new Page<>(current, 5);
        Page<Blog> blogPage = query()
                .eq("user_id", userId)
                .orderByDesc("create_time")
                .page(page);

        List<Blog> blogs = blogPage.getRecords();
        if (blogs == null || blogs.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        List<BlogVo> blogVos = blogs.stream()
                .map(blog -> {
                    BlogVo blogVo = new BlogVo();
                    BeanUtils.copyProperties(blog, blogVo);

                    User user = userService.getById(blog.getUserId());
                    blogVo.setIcon(user.getIcon());
                    blogVo.setName(user.getNickName());

                    isBlogLiked(blogVo);

                    return blogVo;
                })
                .collect(Collectors.toList());

        return Result.ok(blogVos);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSuccess = save(blog);
        if (!isSuccess) {
            return Result.fail("发布失败！");
        }
        //查询粉丝，推送
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();

        for (Follow follow : follows) {
            Long userId = follow.getUserId();
            String key = "feed:" + userId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }



        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 2.查询收件箱 ZREVRANGEBYSCORE key Max Min LIMIT offset count
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        // 3.非空判断
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
        // 4.解析数据：blogId、minTime（时间戳）、offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0; // 2
        int os = 1; // 2
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) { // 5 4 4 2 2
            // 4.1.获取id
            ids.add(Long.valueOf(tuple.getValue()));
            // 4.2.获取分数(时间戳）
            long time = tuple.getScore().longValue();
            if(time == minTime){
                os++;
            }else{
                minTime = time;
                os = 1;
            }
        }

        // 5.根据id查询blog
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();

        //查询每条博客的相关用户信息和点赞
        List<BlogVo> blogVos = blogs.stream().map(blog -> {
            BlogVo blogVo = new BlogVo();
            BeanUtils.copyProperties(blog, blogVo);

            Long blogUserId = blog.getUserId();
            User user = userService.getById(blogUserId);
            blogVo.setIcon(user.getIcon());
            blogVo.setName(user.getNickName());
            isBlogLiked(blogVo);
            return blogVo;
        }).collect(Collectors.toList());

        // 6.封装并返回
        ScrollResult r = new ScrollResult();
        r.setList(blogVos);
        r.setOffset(os);
        r.setMinTime(minTime);

        return Result.ok(r);
    }
}
