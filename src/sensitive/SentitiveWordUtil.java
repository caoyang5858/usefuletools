package com.qf.socialforum.common.sensitive;

import com.qf.socialforum.dao.mapper.SensitiveWordMapper;
import com.qf.socialforum.dao.model.CommonSensitiveWord;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.*;

/**
 * 创建时间：2016年8月30日 下午3:01:12
 *
 * 思路： 创建一个FilterSet，枚举了0~65535的所有char是否是某个敏感词开头的状态
 *
 * 判断是否是 敏感词开头 | | 是 不是 获取头节点 OK--下一个字 然后逐级遍历，DFA算法
 *
 * @author andy
 * @version 2.2
 */
@Component
public class SentitiveWordUtil implements InitializingBean {

	@Resource
	private SensitiveWordMapper sensitiveWordMapper;

	private static final FilterSet set = new FilterSet(); // 存储首字
	private static final Map<Integer, WordNode> nodes = new HashMap<Integer, WordNode>(1024, 1); // 存储节点
	private static final Set<Integer> stopwdSet = new HashSet<>(); // 停顿词
	private static final char SIGN = '*'; // 敏感词过滤替换

	public void reflesh() {
		// 获取敏感词
		List<CommonSensitiveWord> sensitiveWords = sensitiveWordMapper.selectAll();

		List<WordAndReplacement> words = new ArrayList<>();

		for (CommonSensitiveWord sensitiveWord : sensitiveWords) {

			WordAndReplacement word = new WordAndReplacement();
			word.setSensitiveWord(sensitiveWord.getFind());
			word.setRepacement(sensitiveWord.getReplace());

			words.add(word);
		}


		addSensitiveWord(words);

		String stopWord = "!.,#$%&*()|?/@\"';[]{}+~-_=^<>　！ 。，￥（）？、“‘；【】——……《》";
		List<String> stopWordList = new ArrayList<>();
		for (int i = 0; i < stopWord.length(); i++) {
			stopWordList.add(String.valueOf(stopWord.charAt(i)));
		}
		addStopWord(stopWordList);
	}

	/**
	 * 增加停顿词
	 *
	 * @param words
	 */
	private static void addStopWord(final List<String> words) {
		if (!isEmpty(words)) {
			char[] chs;
			for (String curr : words) {
				chs = curr.toCharArray();
				for (char c : chs) {
					stopwdSet.add(charConvert(c));
				}
			}
		}
	}

	/**
	 * 添加DFA节点
	 *
	 * @param words
	 */
	private static void addSensitiveWord(final List<WordAndReplacement> words) {
		if (!isEmpty(words)) {
			char[] chs;
			int fchar;
			int lastIndex;
			WordNode fnode; // 首字母节点
			for (WordAndReplacement wordAndReplacement : words) {
				  String word = wordAndReplacement.getSensitiveWord();
				  String repalcement = wordAndReplacement.getRepacement();
					chs = word.toCharArray();
					fchar = charConvert(chs[0]);
					if (!set.contains(fchar)) {// 没有首字定义
						set.add(fchar);// 首字标志位 可重复add,反正判断了，不重复了
						fnode = new WordNode(fchar, chs.length == 1);
						nodes.put(fchar, fnode);
					} else {
						fnode = nodes.get(fchar);
						if (!fnode.isLast() && chs.length == 1) {
							fnode.setLast(true);
							fnode.setRepacement(repalcement);
						}
					}
					lastIndex = chs.length - 1;
					for (int i = 1; i < chs.length; i++) {
						boolean isLast = i == lastIndex;
						fnode = fnode.addIfNoExist(charConvert(chs[i]), isLast);
						if (isLast){
							fnode.setRepacement(repalcement);
						}
					}
			}
		}
	}

	/**
	 * 过滤判断 将敏感词转化为成屏蔽词
	 *
	 * @param src
	 * @return
	 */
	public static final String doFilter(final String src,boolean useSignReplace) {
		if (set != null && nodes != null) {
			char[] chs = src.toCharArray();
			int length = chs.length;
			int currc; // 当前检查的字符
			int cpcurrc; // 当前检查字符的备份
			int k;
			WordNode node;
			for (int i = 0; i < length; i++) {
				currc = charConvert(chs[i]);
				if (!set.contains(currc)) {
					continue;
				}
				node = nodes.get(currc);// 日 2
				if (node == null)// 其实不会发生，习惯性写上了
					continue;
				boolean couldMark = false;
				int markNum = -1;
				String replacement = null;
				if (node.isLast()) {// 单字匹配（日）
					couldMark = true;
					markNum = 0;
					replacement = node.getRepacement();
				}
				// 继续匹配（日你/日你妹），以长的优先
				// 你-3 妹-4 夫-5
				k = i;
				cpcurrc = currc; // 当前字符的拷贝
				for (; ++k < length;) {
					int temp = charConvert(chs[k]);
					if (temp == cpcurrc)
						continue;
					if (stopwdSet != null && stopwdSet.contains(temp))
						continue;
					node = node.querySub(temp);
					if (node == null)// 没有了
						break;
					if (node.isLast()) {
						couldMark = true;
						markNum = k - i;// 3-2
						replacement = node.getRepacement();
					}
					cpcurrc = temp;
				}
				if (couldMark) {
					if (isEmptyStr(replacement)){
						useSignReplace=true;//没有设置替换词,强行使用替换字符的策略
					}
					if (useSignReplace) {
						for (k = 0; k <= markNum; k++) {  //from index i to i+markNum
							chs[k + i] = SIGN;
						}
						i = i + markNum;
					}else{
						char[] partOne = Arrays.copyOfRange(chs,0,i);
						char[] partTwo = replacement.toCharArray(); //使用替换词
						char[] partThree = Arrays.copyOfRange(chs,i+markNum+1,chs.length);
						char[] result = concatAll(partOne,partTwo,partThree);
						i= partOne.length + partTwo.length - 1; //for循环的结尾会++i
						chs = result;
						length = chs.length;
					}
				}
			}
			return new String(chs);
		}

		return src;
	}


	public static boolean isEmptyStr(String str){
		if (str==null||str.trim().equals("")){
			return true;
		}
		return false;
	}

	/**
	 * 是否包含敏感词
	 *
	 * @param src
	 * @return
	 */
	public static final boolean isContains(final String src) {
		if (set != null && nodes != null) {
			char[] chs = src.toCharArray();
			int length = chs.length;
			int currc; // 当前检查的字符
			int cpcurrc; // 当前检查字符的备份
			int k;
			WordNode node;
			for (int i = 0; i < length; i++) {
				currc = charConvert(chs[i]);
				if (!set.contains(currc)) {
					continue;
				}
				node = nodes.get(currc);// 日 2
				if (node == null)// 其实不会发生，习惯性写上了
					continue;
				boolean couldMark = false;
				if (node.isLast()) {// 单字匹配（日）
					couldMark = true;
				}
				// 继续匹配（日你/日你妹），以长的优先
				// 你-3 妹-4 夫-5
				k = i;
				cpcurrc = currc;
				for (; ++k < length;) {
					int temp = charConvert(chs[k]);
					if (temp == cpcurrc)
						continue;
					if (stopwdSet != null && stopwdSet.contains(temp))
						continue;
					node = node.querySub(temp);
					if (node == null)// 没有了
						break;
					if (node.isLast()) {
						couldMark = true;
					}
					cpcurrc = temp;
				}
				if (couldMark) {
					return true;
				}
			}
		}

		return false;
	}

	/**
	 * 大写转化为小写 全角转化为半角
	 *
	 * @param src
	 * @return
	 */
	private static int charConvert(char src) {
		int r = BCConvert.qj2bj(src);
		return (r >= 'A' && r <= 'Z') ? r + 32 : r;
	}

	/**
	 * 判断一个集合是否为空
	 *
	 * @param col
	 * @return
	 */
	public static <T> boolean isEmpty(final Collection<T> col) {
		if (col == null || col.isEmpty()) {
			return true;
		}
		return false;
	}

	@Override
	public void afterPropertiesSet() throws Exception {
		reflesh();
	}


	public static  char[] concatAll(char[] first, char[]... rest) {
		int totalLength = first.length;
		for (char[] array : rest) {
			totalLength += array.length;
		}
		char[] result = Arrays.copyOf(first, totalLength);
		int offset = first.length;
		for (char[] array : rest) {
			System.arraycopy(array, 0, result, offset, array.length);
			offset += array.length;
		}
		return result;
	}


//
//	public static void main(String[] args) {
//
//		String sss=null;
//		System.out.println(isEmptyStr(sss));
//
//		SentitiveWordUtil util = new SentitiveWordUtil();
//		util.reflesh();
//		System.out.println(util.doFilter("bd",false));
//		System.out.println(util.doFilter("abd",false));
//		System.out.println(util.doFilter("abc",false));
//		System.out.println(util.doFilter("aaaaabc",false));
//		System.out.println(util.doFilter("baaaaabc",false));
//		System.out.println(util.doFilter("fabc",false));
//		System.out.println(util.doFilter("fabcf",false));
//		System.out.println(util.doFilter("abcf",false));
//
//		System.out.println(util.doFilter("abdf",false));
//
//		String a = "abc";
//		char[] ch = a.toCharArray();
//
//		char[] arr1 = Arrays.copyOfRange(ch,0,0);
//		char[] arr2 = "replacement".toCharArray();
//		char[] arr3 = Arrays.copyOfRange(ch,3,3);
//
//
//		char[] result = concatAll(arr1,arr2,arr3);
//		System.out.println(new String(result));
//
//	}


}