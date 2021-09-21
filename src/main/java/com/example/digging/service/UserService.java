package com.example.digging.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import com.example.digging.adapter.jwt.TokenProvider;
import com.example.digging.domain.entity.*;
import com.example.digging.domain.network.TokenDto;
import com.example.digging.domain.network.exception.DuplicateMemberException;
import com.example.digging.domain.network.UserDto;
import com.example.digging.domain.network.request.LoginRequest;
import com.example.digging.domain.network.response.GetPostNumByTypeResponse;
import com.example.digging.domain.network.response.PostsResponse;
import com.example.digging.domain.network.response.TotalTagResponse;
import com.example.digging.domain.network.response.UserApiResponse;
import com.example.digging.domain.repository.*;
import com.example.digging.util.SecurityUtil;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    private final TokenProvider tokenProvider;
    private final AuthenticationManagerBuilder authenticationManagerBuilder;

    private final RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private TagsRepository tagsRepository;

    @Autowired
    private PostsRepository postsRepository;

    @Autowired
    private PostTagRepository postTagRepository;

    @Autowired
    private PostTextRepository postTextRepository;

    @Autowired
    private PostImgRepository postImgRepository;

    @Autowired
    private PostLinkRepository postLinkRepository;

    @Autowired
    private UserHasPostsRepository userHasPostsRepository;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder, TokenProvider tokenProvider, AuthenticationManagerBuilder authenticationManagerBuilder, RefreshTokenRepository refreshTokenRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.authenticationManagerBuilder = authenticationManagerBuilder;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    @Transactional
    public User signup(String oauthId, String username, String email, String provider) {

        if (userRepository.findOneWithAuthoritiesByOauthId(oauthId).orElse(null) != null) {
            throw new DuplicateMemberException("username : " + username + "\nprovider : " + userRepository.findByUsernameAndProvider(username, provider).getProvider() + "\nstatus : 이미 가입되어 있는 유저입니다.");
        }

        //빌더 패턴의 장점
        Authority authority = Authority.builder()
                .authorityName("ROLE_USER")
                .build();

        String makeNewUsername;
        List<User> sameUsernameList = userRepository.findByUsernameStartsWith(username);
        int sameUsernameNum = sameUsernameList.size();
        if (sameUsernameNum > 0 ) {
            makeNewUsername = username +  Integer.toString(sameUsernameNum + 1);
        } else {
            makeNewUsername = username;
        }

        User user = User.builder()
                .username(makeNewUsername)
                .password(passwordEncoder.encode(username))
                .email(email)
                .provider(provider)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .oauthId(oauthId)
                .authorities(Collections.singleton(authority))
                .activated(true)
                .build();

        return userRepository.save(user);
    }

    @Transactional
    public TokenDto login(LoginRequest request) {
        LoginRequest body = request;
        // 1. Login ID/PW 를 기반으로 AuthenticationToken 생성
        UsernamePasswordAuthenticationToken authenticationToken =
                new UsernamePasswordAuthenticationToken(body.getUsername(), body.getUsername());
        //username+provider

        // 2. 실제로 검증 (사용자 비밀번호 체크) 이 이루어지는 부분
        //    authenticate 메서드가 실행이 될 때 CustomUserDetailsService 에서 만들었던 loadUserByUsername 메서드가 실행됨
        Authentication authentication = authenticationManagerBuilder.getObject().authenticate(authenticationToken);

        // 3. 인증 정보를 기반으로 JWT 토큰 생성
        TokenDto jwt = tokenProvider.createToken(authentication);

         // 4. RefreshToken 저장
        RefreshToken savetoken = RefreshToken.builder()
                .token(jwt.getRefreshToken())
                .createdAt(LocalDateTime.now())
                .updatedAt(jwt.getAccessTokenExpiresIn())
                .username(authentication.getName())
                .build();

        refreshTokenRepository.save(savetoken);

        // 5. 토큰 발급
        return jwt;
    }

    @Transactional
    public TokenDto reissue(String access, String refresh) {
        // 1. Refresh Token 검증
        if (!tokenProvider.validateToken(refresh)) {
            throw new RuntimeException("Refresh Token 이 유효하지 않습니다.");
        }

        // 2. Access Token 에서 UserName 가져오기
        Authentication authentication = tokenProvider.getAuthentication(access);

        // 3. 저장소에서 Member ID 를 기반으로 Refresh Token 값 가져옴
        RefreshToken refreshToken = refreshTokenRepository.findById(authentication.getName())
                .orElseThrow(() -> new RuntimeException("로그아웃 된 사용자입니다."));

        // 4. Refresh Token 일치하는지 검사
        if (!refreshToken.getToken().equals(refresh)) {
            throw new RuntimeException("토큰의 유저 정보가 일치하지 않습니다.");
        }

        // 5. 새로운 토큰 생성
        TokenDto tokenDto = tokenProvider.createToken(authentication);

        // 6. 저장소 정보 업데이트
        RefreshToken newRefreshToken = refreshToken.updateValue(tokenDto.getRefreshToken(), LocalDateTime.now(), tokenDto.getAccessTokenExpiresIn());
        refreshTokenRepository.save(newRefreshToken);

        // 토큰 발급
        return tokenDto;
    }

    @Transactional(readOnly = true)
    public UserDto getUserInfoWithAutorities() {

        Optional<User> userInfo = SecurityUtil.getCurrentUsername().flatMap(userRepository::findOneWithAuthoritiesByUsername);

        RefreshToken refreshToken = refreshTokenRepository.findById(userInfo.get().getUsername())
                .orElseThrow(() -> new RuntimeException("로그아웃 된 사용자입니다."));



        return userInfo
                .map(user -> {
                    UserDto userDto = UserDto.builder()
                            .email(user.getEmail())
                            .interest(user.getInterest())
                            .userId(user.getUserId())
                            .oauthId(user.getOauthId())
                            .username(user.getUsername())
                            .provider(user.getProvider())
                            .createdAt(user.getCreatedAt())
                            .updatedAt(user.getUpdatedAt())
                            .activated(user.getActivated())
                            .refreshTokenCreatedAt(refreshToken.getCreatedAt())
                            .refreshTokenUpdatedAt(refreshToken.getUpdatedAt())
                            .build();
                    return userDto;
                })
                .orElseThrow();
    }

    public PostsResponse deletePost(Integer postid) {
        User userInfo = SecurityUtil.getCurrentUsername().flatMap(userRepository::findOneWithAuthoritiesByUsername)
                .orElseThrow(() -> new RuntimeException("token 오류 입니다. 사용자를 찾을 수 없습니다."));

        Optional<UserHasPosts> optional = userHasPostsRepository.findByUser_UserIdAndPostsPostId(userInfo.getUserId(), postid);

        return optional
                .map(opt -> {
                    Posts posts = opt.getPosts();

                    List<PostTag> postTagList = posts.getPostTagList();
                    postsRepository.delete(posts);
                    userHasPostsRepository.delete(opt);

                    for (int i =0; i<postTagList.size(); i++) {
                        postTagRepository.delete(postTagList.get(i));
                        if(postTagList.get(i).getTags().getPostTagList().isEmpty()){
                            tagsRepository.delete(postTagList.get(i).getTags());
                        }

                    }


                    PostsResponse postsResponse = PostsResponse.builder()
                            .resultCode("Delete Success")
                            .build();
                    return postsResponse;


                })
                .orElseGet(
                        ()->{
                            PostsResponse erros = PostsResponse.builder()
                                    .resultCode("Error : 데이터 없음")
                                    .build();
                            return erros;
                        }
                );


    }

    public TotalTagResponse getUserTotalTags() {
        User userInfo = SecurityUtil.getCurrentUsername().flatMap(userRepository::findOneWithAuthoritiesByUsername)
                .orElseThrow(() -> new RuntimeException("token 오류 입니다. 사용자를 찾을 수 없습니다."));

        List<Tags> userTagList = tagsRepository.findAllByUser_UserId(userInfo.getUserId());
        int userTagNum = userTagList.size();
        ArrayList<String> tagStr = new ArrayList<String>();

        for (int i =0;i<userTagNum;i++) {
            tagStr.add(userTagList.get(i).getTags());
        }

        if (userTagNum>0){
            TotalTagResponse totalTagResponse = TotalTagResponse.builder()
                    .resultCode("Success")
                    .totalNum(userTagNum)
                    .totalTags(tagStr)
                    .build();
            return totalTagResponse;
        }else{
            TotalTagResponse totalTagResponse = TotalTagResponse.builder()
                    .resultCode("데이터 없음")
                    .build();
            return totalTagResponse;
        }


    }

    public PostsResponse setLike(Integer postid) {
        User userInfo = SecurityUtil.getCurrentUsername().flatMap(userRepository::findOneWithAuthoritiesByUsername)
                .orElseThrow(() -> new RuntimeException("token 오류 입니다. 사용자를 찾을 수 없습니다."));

        Optional<UserHasPosts> optional = userHasPostsRepository.findByUser_UserIdAndPostsPostId(userInfo.getUserId(), postid);

        return optional
                .map(opt -> {
                    Posts posts = opt.getPosts();
                    posts.setIsLike(!posts.getIsLike())
                            .setUpdatedAt(LocalDateTime.now())
                    ;
                    if(posts.getIsText()==Boolean.TRUE){
                        PostText postText = postTextRepository.findByPostsPostId(opt.getPosts().getPostId());
                        postTextRepository.save(postText.setUpdatedAt(LocalDateTime.now()));
                    }

                    if(posts.getIsLink()==Boolean.TRUE){
                        PostLink postLink = postLinkRepository.findByPostsPostId(opt.getPosts().getPostId());
                        postLinkRepository.save(postLink.setUpdatedAt(LocalDateTime.now()));
                    }

                    if(posts.getIsImg()==Boolean.TRUE){
                        PostImg postImg = postImgRepository.findByPostsPostId(opt.getPosts().getPostId());
                        postImgRepository.save(postImg.setUpdatedAt(LocalDateTime.now()));
                    }
                    return posts;
                })
                .map(posts -> postsRepository.save(posts))
                .map(updatePost -> postres(updatePost))
                .orElseGet(
                        ()->{
                            PostsResponse errres = PostsResponse.builder()
                                    .resultCode("데이터 없음")
                                    .build();
                            return errres;
                        }
                );
    }

    public GetPostNumByTypeResponse getPostNumByType() {

        User userInfo = SecurityUtil.getCurrentUsername().flatMap(userRepository::findOneWithAuthoritiesByUsername)
                .orElseThrow(() -> new RuntimeException("token 오류 입니다. 사용자를 찾을 수 없습니다."));

        List<UserHasPosts> userHasPostsList = userHasPostsRepository.findAllByUser_UserId(userInfo.getUserId());
        int postsNum = userHasPostsList.size();
        Integer textNum = 0; Integer imgNum = 0; Integer linkNum = 0;
        for(int i=0;i<postsNum;i++){
            if (userHasPostsList.get(i).getPosts().getIsText() == Boolean.TRUE) {textNum += 1;}
            if (userHasPostsList.get(i).getPosts().getIsImg() == Boolean.TRUE) {imgNum += 1;}
            if (userHasPostsList.get(i).getPosts().getIsLink() == Boolean.TRUE) {linkNum += 1;}
        }

        return numres(textNum, imgNum, linkNum, userInfo.getUserId());
    }

    private GetPostNumByTypeResponse numres(Integer text, Integer img, Integer link, Integer userid) {
        Optional<User> user = userRepository.findById(userid);
        String username = user.map(finduser -> finduser.getUsername()).orElseGet(()->"User Error");

        GetPostNumByTypeResponse getPostNumByTypeResponse = GetPostNumByTypeResponse.builder()
                .resultCode("Success")
                .userName(username)
                .totalText(text)
                .totalImg(img)
                .totalLink(link)
                .build();

        return getPostNumByTypeResponse;
    }


    private PostsResponse postres(Posts posts) {
        PostsResponse postsResponse = PostsResponse.builder()
                .resultCode("Success")
                .postId(posts.getPostId())
                .isText(posts.getIsText())
                .isImg(posts.getIsImg())
                .isLink(posts.getIsLink())
                .isLike(posts.getIsLike())
                .createdAt(posts.getCreatedAt())
                .createdBy(posts.getCreatedBy())
                .updatedAt(posts.getUpdatedAt())
                .updatedBy(posts.getUpdatedBy())
                .build();
        String typeStr = null;
        if (postsResponse.getIsText() == Boolean.TRUE) {
            typeStr = "text";
        }
        if (postsResponse.getIsImg() == Boolean.TRUE) {
            typeStr = "img";
        }
        if (postsResponse.getIsLink() == Boolean.TRUE) {
            typeStr = "link";
        }
        postsResponse.setType(typeStr);
        return postsResponse;
    }



    @Transactional(readOnly = true)
    public Optional<User> getMyUserWithAuthorities() {
        return SecurityUtil.getCurrentUsername().flatMap(userRepository::findOneWithAuthoritiesByUsername);
    }
}