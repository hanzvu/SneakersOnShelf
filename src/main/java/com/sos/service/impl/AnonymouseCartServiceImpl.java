package com.sos.service.impl;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import javax.mail.MessagingException;
import javax.transaction.Transactional;
import javax.validation.ValidationException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.sos.common.ApplicationConstant.CartStatus;
import com.sos.common.ApplicationConstant.OrderItemStatus;
import com.sos.common.ApplicationConstant.OrderStatus;
import com.sos.common.ApplicationConstant.OrderTimelineType;
import com.sos.common.ApplicationConstant.SaleMethod;
import com.sos.common.ApplicationConstant.VoucherStatus;
import com.sos.dto.CartDTO;
import com.sos.dto.EmailRequest;
import com.sos.entity.Cart;
import com.sos.entity.CartItem;
import com.sos.entity.CustomerInfo;
import com.sos.entity.Order;
import com.sos.entity.OrderItem;
import com.sos.entity.OrderTimeline;
import com.sos.entity.ProductDetail;
import com.sos.entity.Voucher;
import com.sos.exception.ResourceNotFoundException;
import com.sos.repository.CartItemRepository;
import com.sos.repository.CartRepository;
import com.sos.repository.OrderItemRepository;
import com.sos.repository.OrderRepository;
import com.sos.repository.OrderTimelineRepository;
import com.sos.repository.ProductDetailRepository;
import com.sos.repository.VoucherRepository;
import com.sos.service.CartService;
import com.sos.service.DeliveryService;
import com.sos.service.EmailService;
import com.sos.service.util.CartUtils;
import com.sos.service.util.EmailUtil;

@Service
public class AnonymouseCartServiceImpl implements CartService<String> {

	@Autowired
	private CartRepository cartRepository;

	@Autowired
	private CartItemRepository cartItemRepository;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private OrderItemRepository orderItemRepository;
	
	@Autowired
	private ProductDetailRepository productDetailRepository;

	@Autowired
	private OrderTimelineRepository orderTimelineRepository;

	@Autowired
	private VoucherRepository voucherRepository;

	@Autowired
	private DeliveryService deliveryService;

	@Autowired
	private EmailService emailService;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Override
	public CartDTO createCart(String authentication) {
		Date date = new Date();
		Cart cart = new Cart();
		cart.setCartStatus(CartStatus.PENDING);
		cart.setCreateDate(date);
		cart.setUpdateDate(date);
		cart.setToken(generateTokenQuery());
		cartRepository.save(cart);
		return new CartDTO(cart.getId(), cart.getToken(), new ArrayList<>());
	}

	@Override
	public CartDTO getCartDTOById(int id, String authentication) {
		CartDTO rs = cartRepository.findCartDTO(id, CartStatus.PENDING, authentication)
				.orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy giỏ hàng."));
		rs.setItems(cartRepository.findAllCartItemDTOByCartId(id));
		return rs;
	}

	@Override
	public void addToCart(int id, int productId, int quantity, String authentication) {
		Cart cart = cartRepository.findCartId(id, CartStatus.PENDING, authentication)
				.orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy giỏ hàng."));

		ProductDetail productDetail = productDetailRepository.findByProductDetailId(productId)
				.orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy sản phẩm."));

		if (quantity < 1) {
			throw new ValidationException("Số lượng đặt hàng không hợp lệ.");
		}

		CartItem cartItem = cartItemRepository.findByCartIdAndProductDetailId(cart.getId(), productDetail.getId())
				.orElseGet(() -> new CartItem(cart, productDetail, 0));
		cartItem.setQuantity(cartItem.getQuantity() + quantity);
		if (cartItem.getQuantity() > productDetail.getQuantity()) {
			throw new ValidationException("Sản phẩm tạm hết hàng.");
		}

		cartItemRepository.save(cartItem);
	}

	@Transactional
	@Override
	public void changeCartItemQuantity(int id, int quantity, String authentication) {
		CartItem cartItem = cartItemRepository.findCartItemId(id, CartStatus.PENDING, authentication)
				.orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy giỏ hàng."));
		ProductDetail productDetail = productDetailRepository.findByCartItemId(cartItem.getId())
				.orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy sản phẩm."));

		if (productDetail.getQuantity() < quantity) {
			throw new ValidationException(String.format("Sản phẩm chỉ còn lại %s chiếc.", productDetail.getQuantity()));
		}

		if (cartItemRepository.changeCartItemQuantity(cartItem.getId(), quantity) == 0) {
			throw new ValidationException("Có lỗi xảy ra, hãy thử lại sau.");
		}
	}

	@Override
	public void deleteCartItem(int id, String authentication) {
		CartItem cartItem = cartItemRepository.findCartItemId(id, CartStatus.PENDING, authentication)
				.orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy giỏ hàng."));
		cartItemRepository.deleteById(cartItem.getId());
	}

	@Transactional
	@Override
	public void deleteAllCartItem(int cartId, String authentication) {
		Cart cart = cartRepository.findCartId(cartId, CartStatus.PENDING, authentication)
				.orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy giỏ hàng."));
		cartItemRepository.deleteCartItemByCartId(cart.getId());
	}

	@Transactional
	@Override
	public synchronized Order submitCart(int id, CustomerInfo customerInfo, String email, SaleMethod saleMethod,
			Voucher voucher, String authentication) throws JsonMappingException, JsonProcessingException {
		Cart cart = cartRepository.findCart(id, CartStatus.PENDING, authentication)
				.orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy giỏ hàng."));

		List<CartItem> cartItems = cartItemRepository.findCartItemsByCartId(cart.getId());

		if (cartItems.size() < 1) {
			throw new ValidationException("Không có sản phẩm nào trong giỏ.");
		}

		Date now = new Date();
		long total = 0l;

		Order order = new Order();
		List<OrderItem> orderItems = new ArrayList<>();

		for (CartItem cartItem : cartItems) {
			if (cartItem.getQuantity() < 1) {
				throw new ValidationException("Số lượng đặt hàng không hợp lệ");
			}

			if (cartItem.getQuantity() > cartItem.getProductDetail().getQuantity()) {
				throw new ResourceNotFoundException(String.format("Sản phẩm %s [Cỡ %s] số lượng không đủ đáp ứng",
						cartItem.getProductDetail().getProduct().getName(), cartItem.getProductDetail().getSize()));
			}

			OrderItem orderItem = new OrderItem();
			orderItem.setOrder(order);
			orderItem.setProductDetail(cartItem.getProductDetail());
			orderItem.setQuantity(cartItem.getQuantity());
			orderItem.setOrderItemStatus(OrderItemStatus.APPROVED);
			orderItem.setPrice(cartItem.getProductDetail().getProduct().getSellPrice());
			productDetailRepository.decreaseProductDetailQuantity(orderItem.getProductDetail().getId(),
					orderItem.getQuantity());
			total += orderItem.getPrice() * orderItem.getQuantity();
			orderItems.add(orderItem);
		}

		order.setId(CartUtils.generateOrderId(now, id));
		order.setToken(cart.getToken());
		order.setOrderStatus(OrderStatus.PENDING);
		order.setTotal(total);
		order.setFee(deliveryService.getDeliveryFee(order.getTotal() + order.getSurcharge() - order.getDiscount(),
				customerInfo.getDistrictId(), customerInfo.getWardCode()));
		order.setFullname(customerInfo.getFullname());
		order.setPhone(customerInfo.getPhone());
		order.setCreateDate(now);

		if (voucher != null) {
			Voucher selectedVoucher = voucherRepository
					.findAvailableVoucherById(voucher.getId(), VoucherStatus.ACTIVE, now)
					.orElseThrow(() -> new ValidationException("Mã giảm giá không hợp lệ."));
			if (order.getTotal() < selectedVoucher.getRequiredValue()) {
				throw new ValidationException("Mã giảm giá không hợp lệ.");
			}

			long discount = 0;

			switch (selectedVoucher.getVoucherType()) {
			case PERCENT:
				if (selectedVoucher.getAmount() <= 0 || selectedVoucher.getAmount() > 100) {
					throw new ValidationException("Mã giảm giá không hợp lệ.");
				}
				discount = order.getTotal() * voucher.getAmount() / 100;
				break;
			case DISCOUNT:
				discount = voucher.getAmount();
				break;
			default:
				throw new ValidationException("Mã giảm giá không hợp lệ.");
			}

			if (voucher.getMaxValue() > 0 && discount > voucher.getMaxValue()) {
				discount = voucher.getMaxValue();
			}
			order.setVoucher(selectedVoucher);
			order.setDiscount(discount <= order.getTotal() ? discount : order.getTotal());

			if (voucherRepository.decreateVoucherQuantity(selectedVoucher.getId()) != 1) {
				throw new ValidationException("Mã giảm giá không hợp lệ.");
			}
		}

		order.setProvinceId(customerInfo.getProvinceId());
		order.setDistrictId(customerInfo.getDistrictId());
		order.setWardCode(customerInfo.getWardCode());
		order.setSaleMethod(saleMethod);
		order.setAddress(String.format("%s, %s, %s", customerInfo.getWardName(), customerInfo.getDistrictName(),
				customerInfo.getProvinceName()));
		order.setDetailedAddress(customerInfo.getAddress());
		
		orderRepository.save(order);
		orderItemRepository.saveAll(orderItems);

		OrderTimeline orderTimeline = new OrderTimeline();
		orderTimeline.setCreatedDate(now);
		orderTimeline.setOrder(order);
		orderTimeline.setOrderTimelineType(OrderTimelineType.CREATED);
		orderTimelineRepository.save(orderTimeline);

		if (cartRepository.updateCartStatus(cart.getId(), CartStatus.APPROVED, now) == 0) {
			throw new ResourceNotFoundException("Không tìm thấy giỏ hàng");
		}

		CompletableFuture.runAsync(() -> {
			try {
				emailService.sendEmail(new EmailRequest(new String[] { order.getEmail() }, null, null,
						EmailUtil.getNewOrderEmailSubject(order.getId()), EmailUtil.getNewOrderEmailContent(order),
						true));
			} catch (UnsupportedEncodingException e) {
				e.printStackTrace();
			} catch (MessagingException e) {
				e.printStackTrace();
			}
		});

		return order;
	}

	private String generateTokenQuery() {
		return passwordEncoder.encode(UUID.randomUUID().toString()).replaceAll("/", "_");
	}

}
