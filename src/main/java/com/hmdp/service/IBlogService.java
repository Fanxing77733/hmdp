package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {


    Result queryBlogById(Long id);

    /**
     * 点赞
     * @param id 需要点赞的博客id
     * @return
     */


    Result likeBlog(Long id);

    /**
     * 查询点赞列表top5
     * @param id 需要查询的博客id
     * @return
     */
    Result queryBlogLikes(Long id);

    /**
     * 查询用户博客
     * @param userId
     * @param current
     * @return
     */
    Result queryUserBlogs(Long userId, Integer current);

    Result saveBlog(Blog blog);

    /**
     * 查询关注博客
     * @param max
     * @param offset
     * @return
     */
    Result queryBlogOfFollow(Long max, Integer offset);
}